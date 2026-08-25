package app.multiai.mobile.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyless Web Search → scrape → local rerank pipeline shared by Chat and Work Mode.
 *
 * <p>The search stage walks an ordered chain of keyless providers — DuckDuckGo HTML,
 * DuckDuckGo Lite, Mojeek, Bing RSS, Google News RSS and finally the MediaWiki API — and
 * moves on whenever one is blocked, rate-limited or answers with nothing. Every HTTP call
 * is retried on transient statuses (429/5xx) and read timeouts, so a single gateway 504 no
 * longer fails the tool. The scraper then reads a small number of public result pages
 * directly, strips non-content markup and ranks excerpts by overlap with the original query.
 * This keeps provider keys out of the APK while giving the model actual source text and
 * numbered citations in a single tool result.</p>
 */
public final class WebSearchService {
    private static final String DUCK_DUCK_GO_PROVIDER = "duckduckgo_html";
    private static final String DUCK_DUCK_GO_LITE_PROVIDER = "duckduckgo_lite";
    private static final String MOJEEK_PROVIDER = "mojeek_html";
    private static final String BING_RSS_PROVIDER = "bing_rss";
    private static final String GOOGLE_NEWS_RSS_PROVIDER = "google_news_rss";
    private static final String WIKIPEDIA_PROVIDER = "wikipedia_api";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "Chrome/122 Mobile Safari/537.36 MultiAI-WebSearch/1.0";
    private static final int DEFAULT_RESULT_LIMIT = 5;
    private static final int MAX_RESULT_LIMIT = 5;
    private static final int MAX_SCRAPED_SOURCES = 3;
    private static final int MAX_PAGE_BYTES = 900_000;
    private static final int MAX_FETCH_CONTENT = 6_000;
    private static final int MAX_SEARCH_CONTEXT = 5_500;
    private static final int SEARCH_CONNECT_TIMEOUT_MS = 6_000;
    private static final int SEARCH_READ_TIMEOUT_MS = 8_000;
    /** Скрейп идёт по трём источникам подряд — держим страницу на коротком поводке. */
    private static final int PAGE_CONNECT_TIMEOUT_MS = 6_000;
    private static final int PAGE_READ_TIMEOUT_MS = 8_000;
    /** url_fetch запрошен моделью явно и всего один — тут можно потерпеть дольше. */
    private static final int DIRECT_FETCH_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DIRECT_FETCH_READ_TIMEOUT_MS = 15_000;
    /** Сколько раз повторяем один и тот же HTTP-запрос при временной ошибке. */
    private static final int MAX_HTTP_ATTEMPTS = 2;
    private static final long RETRY_BASE_DELAY_MS = 400L;
    /**
     * Общий бюджет фазы поиска. Провайдеров в цепочке шесть, и без ограничения
     * серия таймаутов растянула бы один tool call на минуты.
     */
    private static final long SEARCH_PHASE_BUDGET_MS = 18_000L;
    /**
     * Бюджет фазы скрейпинга. Три медленные страницы по connect+read давали
     * ещё ~40 секунд сверх поиска, и модель успевала получить таймаут раньше,
     * чем тул возвращал уже собранную выдачу.
     */
    private static final long SCRAPE_PHASE_BUDGET_MS = 14_000L;

    /**
     * Коды, при которых повтор имеет смысл: шлюз/лимиты/перегрузка.
     * Именно 504 от html.duckduckgo.com делал поиск «через раз».
     */
    private static final Set<Integer> RETRYABLE_STATUSES = new HashSet<>(java.util.Arrays.asList(
            408, 425, 429, 500, 502, 503, 504, 509, 520, 521, 522, 523, 524, 598, 599));

    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "(?is)<(?:a|div)[^>]*class\\s*=\\s*[\\\"'][^\\\"']*result__snippet[^\\\"']*[\\\"'][^>]*>(.*?)</(?:a|div)>");
    private static final Pattern DDG_LITE_SNIPPET = Pattern.compile(
            "(?is)<td[^>]*class\\s*=\\s*[\\\"']?[^\\\"'>]*result-snippet[^>]*>(.*?)</td>");
    private static final Pattern MOJEEK_SNIPPET = Pattern.compile(
            "(?is)<p[^>]*class\\s*=\\s*[\\\"']s[\\\"'][^>]*>(.*?)</p>");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a>");
    private static final Pattern HREF_ATTR = Pattern.compile(
            "(?is)href\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern CLASS_ATTR = Pattern.compile(
            "(?is)class\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']");
    private static final Pattern RSS_ITEM = Pattern.compile("(?is)<item\\b[^>]*>(.*?)</item>");
    private static final Pattern RSS_TITLE = Pattern.compile("(?is)<title(?:\\s[^>]*)?>(.*?)</title>");
    private static final Pattern RSS_LINK = Pattern.compile("(?is)<link(?:\\s[^>]*)?>(.*?)</link>");
    private static final Pattern RSS_DESCRIPTION = Pattern.compile(
            "(?is)<description(?:\\s[^>]*)?>(.*?)</description>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern OG_TITLE = Pattern.compile(
            "(?is)<meta[^>]+(?:property|name)\\s*=\\s*[\\\"']og:title[\\\"'][^>]+content\\s*=\\s*[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    /** IPv4/IPv6 literal host — решается без DNS. */
    private static final Pattern LITERAL_ADDRESS = Pattern.compile("^(?:\\[?[0-9a-f:]+]?|[0-9.]+)$");

    private WebSearchService() {
    }

    /** Executes the complete search pipeline and returns model-ready excerpts and sources. */
    public static JSONObject search(JSONObject args) {
        String query = args == null ? "" : args.optString("query", "").trim();
        if (query.isEmpty()) return error("web_search: query is required");
        if (query.length() > 500) query = query.substring(0, 500);

        int requestedLimit = args == null ? DEFAULT_RESULT_LIMIT
                : args.optInt("max_results", DEFAULT_RESULT_LIMIT);
        int limit = Math.max(1, Math.min(MAX_RESULT_LIMIT, requestedLimit));
        String language = args == null ? "ru" : args.optString("language", "ru");

        try {
            SearchDiscovery discovery = discoverSearchResults(query, language, limit);
            List<SearchResult> results = discovery.results;
            if (results.isEmpty()) {
                // Пустая выдача — это не «ошибка тула»: возвращаем валидный
                // результат с пояснением, чтобы модель могла переформулировать
                // запрос вместо показа красной карточки.
                JSONObject out = new JSONObject();
                out.put("query", query);
                out.put("provider", discovery.provider);
                out.put("results", new JSONArray());
                out.put("sources", new JSONArray());
                out.put("message", "Поиск не нашёл доступных публичных результатов.");
                if (!discovery.failures.isEmpty()) {
                    out.put("provider_diagnostics", android.text.TextUtils.join("; ", discovery.failures));
                }
                return out;
            }

            List<String> queryTokens = meaningfulTokens(query);
            List<SearchResult> scraped = new ArrayList<>();
            List<String> scrapeFailures = new ArrayList<>();
            long scrapeDeadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SCRAPE_PHASE_BUDGET_MS);
            for (SearchResult result : results) {
                if (scraped.size() >= MAX_SCRAPED_SOURCES) break;
                if (System.nanoTime() > scrapeDeadline) {
                    scrapeFailures.add("scrape budget exhausted");
                    break;
                }
                // One unreachable page must never fail the tool: the ranked
                // result list is already useful on its own. Letting fetchPage
                // throw here turned a single 403/504/timeout on the first link
                // into a red "web_search failed" card.
                Page page;
                try {
                    page = fetchPage(result.url, queryTokens);
                } catch (Exception pageError) {
                    scrapeFailures.add(shortHost(result.url) + ": " + safeMessage(pageError));
                    continue;
                }
                if (page == null || page.content.isEmpty()) continue;
                result.title = result.title.isEmpty() ? page.title : result.title;
                result.excerpt = page.excerpt;
                result.content = page.content;
                result.score = page.score;
                scraped.add(result);
            }

            // Scraped sources with the most query-relevant excerpt come first.
            Collections.sort(scraped, (left, right) -> Integer.compare(right.score, left.score));
            JSONArray sources = new JSONArray();
            java.util.Map<String, Integer> citationByUrl = new java.util.HashMap<>();
            StringBuilder modelContext = new StringBuilder();
            for (int i = 0; i < scraped.size(); i++) {
                SearchResult source = scraped.get(i);
                int sourceId = i + 1;
                citationByUrl.put(source.url, sourceId);
                JSONObject item = new JSONObject();
                item.put("id", sourceId);
                item.put("title", source.displayTitle());
                item.put("url", source.url);
                item.put("excerpt", source.excerpt);
                item.put("score", source.score);
                sources.put(item);

                appendSourceContext(modelContext, sourceId, source);
            }

            JSONArray uiResults = new JSONArray();
            for (SearchResult result : results) {
                JSONObject item = new JSONObject();
                item.put("title", result.displayTitle());
                item.put("url", result.url);
                item.put("snippet", result.excerpt.isEmpty() ? result.snippet : result.excerpt);
                Integer citationId = citationByUrl.get(result.url);
                if (citationId != null) item.put("citation", citationId);
                if (!result.content.isEmpty()) item.put("content", result.content);
                uiResults.put(item);
            }

            // Scraping is best-effort. When every page refused us, the model
            // still has to receive something to reason about, so fall back to
            // the provider snippets instead of an empty context block.
            if (scraped.isEmpty()) appendSnippetContext(modelContext, results);

            JSONObject out = new JSONObject();
            out.put("query", query);
            out.put("provider", discovery.provider + (scraped.isEmpty() ? "" : "+local_scraper"));
            out.put("results", uiResults);
            out.put("sources", sources);
            out.put("scraped_count", scraped.size());
            out.put("citation_instruction", "When using a source, cite its number in square brackets, for example [1].");
            out.put("context", modelContext.toString());
            if (!scrapeFailures.isEmpty()) {
                out.put("scrape_diagnostics", android.text.TextUtils.join("; ", scrapeFailures));
            }
            return out;
        } catch (Exception error) {
            return error("web_search failed: " + safeMessage(error));
        }
    }

    /** Fetches and cleans one public page for the url_fetch tool. */
    public static JSONObject fetch(JSONObject args) {
        String rawUrl = args == null ? "" : args.optString("url", "").trim();
        if (rawUrl.isEmpty()) return error("url_fetch: url is required");
        if (!isPublicHttpUrl(rawUrl)) return error("url_fetch: URL must be a public http(s) address");

        try {
            Page page = fetchPage(rawUrl, Collections.emptyList(),
                    DIRECT_FETCH_CONNECT_TIMEOUT_MS, DIRECT_FETCH_READ_TIMEOUT_MS);
            if (page == null || page.content.isEmpty()) return error("url_fetch: page content is unavailable");
            JSONObject out = new JSONObject();
            out.put("title", page.title);
            out.put("url", rawUrl);
            out.put("content", truncate(page.content, MAX_FETCH_CONTENT));
            out.put("snippet", page.excerpt);
            out.put("length", page.content.length());
            out.put("source", "local_scraper");
            return out;
        } catch (Exception error) {
            return error("url_fetch failed: " + safeMessage(error));
        }
    }

    /**
     * Walks the keyless provider chain until one of them returns results.
     *
     * <p>Mirrors the LibreChat contract (a search provider list with an ordered
     * fallback) without any API keys: every provider is retried on transient
     * transport failures, and a provider that answers with an empty result set
     * is treated as a miss so the next one still gets a chance. Previously a
     * single DuckDuckGo 504 failed the whole tool, which is why web search
     * worked only every other time.</p>
     */
    private static SearchDiscovery discoverSearchResults(String query, String language, int limit) {
        List<String> failures = new ArrayList<>();
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(SEARCH_PHASE_BUDGET_MS);
        PHASE_DEADLINE_NANOS.set(deadline);
        try {
            for (SearchProvider provider : searchProviders()) {
                if (System.nanoTime() > deadline) {
                    failures.add("budget exhausted");
                    break;
                }
                try {
                    List<SearchResult> results = provider.search(query, language, limit);
                    if (results != null && !results.isEmpty()) {
                        return new SearchDiscovery(provider.name(), results, failures);
                    }
                    failures.add(provider.name() + ": no results");
                } catch (Exception providerError) {
                    failures.add(provider.name() + ": " + safeMessage(providerError));
                }
            }
        } finally {
            PHASE_DEADLINE_NANOS.remove();
        }
        return new SearchDiscovery(joinProviderNames(), new ArrayList<>(), failures);
    }

    private interface SearchProvider {
        String name();

        List<SearchResult> search(String query, String language, int limit) throws Exception;
    }

    private static List<SearchProvider> searchProviders() {
        List<SearchProvider> providers = new ArrayList<>();
        providers.add(provider(DUCK_DUCK_GO_PROVIDER, WebSearchService::searchDuckDuckGoHtml));
        providers.add(provider(DUCK_DUCK_GO_LITE_PROVIDER, WebSearchService::searchDuckDuckGoLite));
        providers.add(provider(MOJEEK_PROVIDER, WebSearchService::searchMojeek));
        providers.add(provider(BING_RSS_PROVIDER, WebSearchService::searchBingRss));
        providers.add(provider(GOOGLE_NEWS_RSS_PROVIDER, WebSearchService::searchGoogleNewsRss));
        providers.add(provider(WIKIPEDIA_PROVIDER, WebSearchService::searchWikipedia));
        return providers;
    }

    private static String joinProviderNames() {
        StringBuilder names = new StringBuilder();
        for (SearchProvider provider : searchProviders()) {
            if (names.length() > 0) names.append('+');
            names.append(provider.name());
        }
        return names.toString();
    }

    private interface ProviderCall {
        List<SearchResult> run(String query, String language, int limit) throws Exception;
    }

    private static SearchProvider provider(String name, ProviderCall call) {
        return new SearchProvider() {
            @Override public String name() { return name; }

            @Override public List<SearchResult> search(String query, String language, int limit)
                    throws Exception {
                return call.run(query, language, limit);
            }
        };
    }

    private static List<SearchResult> searchDuckDuckGoHtml(String query, String language, int limit)
            throws Exception {
        String locale = "ru".equalsIgnoreCase(language) ? "ru-ru" : "wt-wt";
        String endpoint = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, "UTF-8") + "&kl=" + locale;
        String html = downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 700_000);
        return collectHtmlResults(html, "result__a", DDG_SNIPPET, limit, true);
    }

    private static List<SearchResult> searchDuckDuckGoLite(String query, String language, int limit)
            throws Exception {
        String locale = "ru".equalsIgnoreCase(language) ? "ru-ru" : "wt-wt";
        String endpoint = "https://lite.duckduckgo.com/lite/?q="
                + URLEncoder.encode(query, "UTF-8") + "&kl=" + locale;
        String html = downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 700_000);
        return collectHtmlResults(html, "result-link", DDG_LITE_SNIPPET, limit, true);
    }

    private static List<SearchResult> searchMojeek(String query, String language, int limit)
            throws Exception {
        String endpoint = "https://www.mojeek.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                + ("ru".equalsIgnoreCase(language) ? "&lb=ru" : "");
        String html = downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 700_000);
        return collectHtmlResults(html, "ta", MOJEEK_SNIPPET, limit, false);
    }

    private static List<SearchResult> searchBingRss(String query, String language, int limit)
            throws Exception {
        String locale = "ru".equalsIgnoreCase(language) ? "ru" : "en";
        String endpoint = "https://www.bing.com/search?format=rss&q="
                + URLEncoder.encode(query, "UTF-8") + "&setlang=" + locale;
        return collectRssResults(
                downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 700_000),
                limit);
    }

    private static List<SearchResult> searchGoogleNewsRss(String query, String language, int limit)
            throws Exception {
        boolean russian = "ru".equalsIgnoreCase(language);
        String endpoint = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8")
                + (russian ? "&hl=ru&gl=RU&ceid=RU:ru" : "&hl=en-US&gl=US&ceid=US:en");
        return collectRssResults(
                downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 700_000),
                limit);
    }

    /** Last resort: the MediaWiki open-search API is stable and never rate-limits us. */
    private static List<SearchResult> searchWikipedia(String query, String language, int limit)
            throws Exception {
        String host = "ru".equalsIgnoreCase(language) ? "ru.wikipedia.org" : "en.wikipedia.org";
        String endpoint = "https://" + host + "/w/api.php?action=query&format=json&list=search"
                + "&srlimit=" + limit + "&srsearch=" + URLEncoder.encode(query, "UTF-8");
        String json = downloadText(endpoint, SEARCH_CONNECT_TIMEOUT_MS, SEARCH_READ_TIMEOUT_MS, 400_000);
        JSONObject queryNode = new JSONObject(json).optJSONObject("query");
        JSONArray hits = queryNode == null ? null : queryNode.optJSONArray("search");
        List<SearchResult> results = new ArrayList<>();
        if (hits == null) return results;
        for (int i = 0; i < hits.length() && results.size() < limit; i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            String title = hit.optString("title", "").trim();
            if (title.isEmpty()) continue;
            String url = "https://" + host + "/wiki/"
                    + URLEncoder.encode(title.replace(' ', '_'), "UTF-8");
            results.add(new SearchResult(title, url, cleanHtml(hit.optString("snippet", ""))));
        }
        return results;
    }

    /** Shared HTML result extraction: anchors filtered by CSS class + ordered snippets. */
    private static List<SearchResult> collectHtmlResults(String html, String anchorClass,
                                                        Pattern snippetPattern, int limit,
                                                        boolean unwrapDuckDuckGo) {
        List<String> snippets = new ArrayList<>();
        if (snippetPattern != null) {
            Matcher snippetMatcher = snippetPattern.matcher(html);
            while (snippetMatcher.find()) snippets.add(cleanHtml(snippetMatcher.group(1)));
        }

        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        int index = 0;
        for (String[] anchor : anchorsWithClass(html, anchorClass)) {
            if (results.size() >= limit) break;
            String url = unwrapDuckDuckGo ? unwrapDuckDuckGoUrl(anchor[0]) : anchor[0];
            String snippet = index < snippets.size() ? snippets.get(index) : "";
            index++;
            if (!isFetchCandidateUrl(url) || !seenUrls.add(url)) continue;
            results.add(new SearchResult(anchor[1], url, snippet));
        }
        return results;
    }

    private static List<SearchResult> collectRssResults(String rss, int limit) {
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        Matcher itemMatcher = RSS_ITEM.matcher(rss);
        while (itemMatcher.find() && results.size() < limit) {
            String item = itemMatcher.group(1);
            String url = cleanRssValue(extractRssTag(item, RSS_LINK));
            if (!isFetchCandidateUrl(url) || !seenUrls.add(url)) continue;
            String title = cleanRssValue(extractRssTag(item, RSS_TITLE));
            String snippet = cleanRssValue(extractRssTag(item, RSS_DESCRIPTION));
            results.add(new SearchResult(title, url, snippet));
        }
        return results;
    }

    /**
     * Extracts {href, text} pairs from anchors whose class list contains the
     * requested token. Provider markup puts href and class in different orders,
     * so a single regex over the whole tag is unreliable.
     */
    private static List<String[]> anchorsWithClass(String html, String anchorClass) {
        List<String[]> anchors = new ArrayList<>();
        String needle = anchorClass == null ? "" : anchorClass.toLowerCase(Locale.US);
        Matcher matcher = ANCHOR.matcher(html);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            if (!needle.isEmpty() && !classMatches(attributes, needle)) continue;
            Matcher href = HREF_ATTR.matcher(attributes);
            if (!href.find()) continue;
            String url = htmlDecode(href.group(1)).trim();
            if (url.isEmpty()) continue;
            anchors.add(new String[]{url, cleanHtml(matcher.group(2))});
        }
        return anchors;
    }

    private static boolean classMatches(String attributes, String needle) {
        Matcher classAttr = CLASS_ATTR.matcher(attributes);
        if (!classAttr.find()) return false;
        String classes = classAttr.group(1).toLowerCase(Locale.US);
        for (String token : classes.split("\\s+")) {
            if (token.equals(needle)) return true;
            // BEM-style provider classes ("result__a js-result-title-link").
            if (needle.length() > 4 && token.contains(needle)) return true;
        }
        return false;
    }

    private static String extractRssTag(String item, Pattern pattern) {
        Matcher matcher = pattern.matcher(item);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String cleanRssValue(String value) {
        if (value == null) return "";
        String withoutCdata = value.replaceAll("(?is)<!\\[CDATA\\[(.*?)]]>", "$1");
        return cleanHtml(withoutCdata);
    }

    private static final class SearchDiscovery {
        final String provider;
        final List<SearchResult> results;
        /** Диагностика по провайдерам, которые не дали результата. */
        final List<String> failures;

        SearchDiscovery(String provider, List<SearchResult> results, List<String> failures) {
            this.provider = provider;
            this.results = results;
            this.failures = failures == null ? new ArrayList<>() : failures;
        }
    }

    private static Page fetchPage(String url, List<String> queryTokens) throws Exception {
        return fetchPage(url, queryTokens, PAGE_CONNECT_TIMEOUT_MS, PAGE_READ_TIMEOUT_MS);
    }

    private static Page fetchPage(String url, List<String> queryTokens,
                                  int connectTimeoutMs, int readTimeoutMs) throws Exception {
        if (!isPublicHttpUrl(url)) return null;
        // Scraping is best-effort across several sources: retrying every slow page
        // would blow the tool's latency budget, so a single attempt is enough here.
        String html = downloadText(url, connectTimeoutMs, readTimeoutMs, MAX_PAGE_BYTES, 1);
        String title = extractTitle(html);
        String content = cleanHtml(removeNonContentBlocks(html));
        if (content.isEmpty()) return null;

        Page page = new Page();
        page.title = title;
        page.content = truncate(content, MAX_FETCH_CONTENT);
        RankedExcerpt ranked = rankExcerpt(content, queryTokens);
        page.excerpt = ranked.text;
        page.score = ranked.score;
        return page;
    }

    /**
     * HTTP GET with bounded retries.
     *
     * <p>Gateway answers (504/502/503) and read timeouts from the keyless search
     * frontends are transient: a short backoff usually succeeds. Non-retryable
     * statuses (403/404/…) fail immediately so the caller can move on to the
     * next provider without wasting time.</p>
     */
    private static String downloadText(String rawUrl, int connectTimeoutMs, int readTimeoutMs,
                                       int maxBytes) throws Exception {
        return downloadText(rawUrl, connectTimeoutMs, readTimeoutMs, maxBytes, MAX_HTTP_ATTEMPTS);
    }

    private static String downloadText(String rawUrl, int connectTimeoutMs, int readTimeoutMs,
                                       int maxBytes, int maxAttempts) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return downloadTextOnce(rawUrl, connectTimeoutMs, readTimeoutMs, maxBytes);
            } catch (HttpStatusException statusError) {
                lastError = statusError;
                if (!RETRYABLE_STATUSES.contains(statusError.status)) throw statusError;
            } catch (java.io.IOException transportError) {
                lastError = transportError;
            }
            // Retrying past the phase deadline only delays the fallback chain:
            // the caller has other providers left and the model is waiting.
            if (attempt < maxAttempts && !phaseBudgetExhausted()) {
                try {
                    Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                break;
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("network error");
    }

    /** Дедлайн текущей фазы, чтобы ретраи не переезжали общий бюджет тула. */
    private static final ThreadLocal<Long> PHASE_DEADLINE_NANOS = new ThreadLocal<>();

    private static boolean phaseBudgetExhausted() {
        Long deadline = PHASE_DEADLINE_NANOS.get();
        return deadline != null && System.nanoTime() > deadline;
    }

    /** Ошибка HTTP-статуса с сохранением кода — нужна для решения о повторе. */
    private static final class HttpStatusException extends java.io.IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private static String downloadTextOnce(String rawUrl, int connectTimeoutMs, int readTimeoutMs,
                                          int maxBytes) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.2");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new HttpStatusException(status);
            }
            InputStream input = new BufferedInputStream(connection.getInputStream());
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8_192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int allowed = Math.min(read, maxBytes - total);
                    if (allowed > 0) output.write(buffer, 0, allowed);
                    total += read;
                    if (total >= maxBytes) break;
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String unwrapDuckDuckGoUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        String href = value.startsWith("//") ? "https:" + value : value;
        Matcher matcher = Pattern.compile("(?:[?&])uddg=([^&]+)").matcher(href);
        if (!matcher.find()) return href;
        try {
            return URLDecoder.decode(matcher.group(1), "UTF-8");
        } catch (Exception ignored) {
            return href;
        }
    }

    /**
     * Cheap syntactic screening for links harvested from a result page.
     *
     * <p>Deliberately does no name resolution. {@link #isPublicHttpUrl} used to
     * be called for every anchor a provider returned, so one query performed a
     * dozen blocking {@code InetAddress} lookups — which have no timeout — long
     * before anything was actually fetched. That alone could exhaust the tool's
     * latency budget. The authoritative SSRF check still runs, but only right
     * before a request leaves the device.</p>
     */
    private static boolean isFetchCandidateUrl(String rawUrl) {
        try {
            URL parsed = new URL(rawUrl);
            String protocol = parsed.getProtocol().toLowerCase(Locale.US);
            if (!"http".equals(protocol) && !"https".equals(protocol)) return false;
            String host = parsed.getHost();
            if (host == null || host.isEmpty()) return false;
            String normalizedHost = host.toLowerCase(Locale.US);
            if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".local")
                    || normalizedHost.endsWith(".internal")) return false;
            // Literal addresses are decided here; hostnames wait for the DNS check.
            if (LITERAL_ADDRESS.matcher(normalizedHost).matches()) {
                return isPublicAddressLiteral(normalizedHost);
            }
            return normalizedHost.indexOf('.') > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPublicAddressLiteral(String host) {
        try {
            return isPublicAddress(InetAddress.getByName(host));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Blocks local/private networks before a model-directed URL is fetched. */
    private static boolean isPublicHttpUrl(String rawUrl) {
        if (!isFetchCandidateUrl(rawUrl)) return false;
        try {
            String host = new URL(rawUrl).getHost();
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        return address != null
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }

    private static String shortHost(String rawUrl) {
        try {
            String host = new URL(rawUrl).getHost();
            return host == null || host.isEmpty() ? "source" : host;
        } catch (Exception ignored) {
            return "source";
        }
    }

    private static String removeNonContentBlocks(String html) {
        return html
                .replaceAll("(?is)<(script|style|noscript|svg|canvas|iframe|form)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<(nav|header|footer|aside)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<!--.*?-->", " ");
    }

    private static String extractTitle(String html) {
        Matcher og = OG_TITLE.matcher(html);
        if (og.find()) return cleanHtml(og.group(1));
        Matcher title = TITLE.matcher(html);
        return title.find() ? cleanHtml(title.group(1)) : "";
    }

    private static String cleanHtml(String value) {
        if (value == null) return "";
        return WHITESPACE.matcher(htmlDecode(TAGS.matcher(value).replaceAll(" "))).replaceAll(" ").trim();
    }

    private static String htmlDecode(String value) {
        if (value == null) return "";
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x2F;", "/");
    }

    private static RankedExcerpt rankExcerpt(String text, List<String> queryTokens) {
        String normalized = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (normalized.isEmpty()) return new RankedExcerpt("", 0);
        if (queryTokens == null || queryTokens.isEmpty()) {
            return new RankedExcerpt(truncate(normalized, 900), 0);
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += 850) {
            int end = Math.min(normalized.length(), start + 1_050);
            if (end < normalized.length()) {
                int sentenceEnd = normalized.lastIndexOf('.', end);
                if (sentenceEnd > start + 350) end = sentenceEnd + 1;
            }
            chunks.add(normalized.substring(start, end));
            if (end >= normalized.length()) break;
        }

        List<RankedExcerpt> ranked = new ArrayList<>();
        for (String chunk : chunks) {
            String lower = chunk.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String token : queryTokens) {
                int from = 0;
                while ((from = lower.indexOf(token, from)) >= 0) {
                    score += 3;
                    from += token.length();
                }
            }
            ranked.add(new RankedExcerpt(chunk, score));
        }
        Collections.sort(ranked, (left, right) -> Integer.compare(right.score, left.score));
        RankedExcerpt best = ranked.get(0);
        String excerpt = truncate(best.text, 1_100);
        if (ranked.size() > 1 && ranked.get(1).score > 0) {
            excerpt = truncate(excerpt + "\n\n" + ranked.get(1).text, 1_500);
        }
        return new RankedExcerpt(excerpt, best.score);
    }

    private static List<String> meaningfulTokens(String query) {
        Set<String> unique = new LinkedHashSet<>();
        for (String raw : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= 2) unique.add(raw);
        }
        return new ArrayList<>(unique);
    }

    /** Fallback model context built from provider snippets when scraping failed. */
    private static void appendSnippetContext(StringBuilder context, List<SearchResult> results) {
        int id = 0;
        for (SearchResult result : results) {
            String snippet = result.excerpt.isEmpty() ? result.snippet : result.excerpt;
            if (snippet.trim().isEmpty()) continue;
            id++;
            if (context.length() >= MAX_SEARCH_CONTEXT) return;
            String block = "[" + id + "] " + result.displayTitle() + "\n"
                    + result.url + "\n"
                    + truncate(snippet, 700) + "\n\n";
            int remaining = MAX_SEARCH_CONTEXT - context.length();
            context.append(block, 0, Math.min(block.length(), remaining));
        }
    }

    private static void appendSourceContext(StringBuilder context, int id, SearchResult source) {
        if (context.length() >= MAX_SEARCH_CONTEXT) return;
        String block = "[" + id + "] " + source.displayTitle() + "\n"
                + source.url + "\n"
                + truncate(source.excerpt, 1_400) + "\n\n";
        int remaining = MAX_SEARCH_CONTEXT - context.length();
        context.append(block, 0, Math.min(block.length(), remaining));
    }

    private static JSONObject error(String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("error", message);
        } catch (Exception ignored) {
        }
        return error;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "network error";
        return message.replace('"', '\'').replace('\n', ' ').trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength).trim() + "…";
    }

    private static final class SearchResult {
        String title;
        final String url;
        final String snippet;
        String excerpt = "";
        String content = "";
        int score;

        SearchResult(String title, String url, String snippet) {
            this.title = title == null ? "" : title;
            this.url = url;
            this.snippet = snippet == null ? "" : snippet;
        }

        String displayTitle() {
            return title.isEmpty() ? url : title;
        }
    }

    private static final class Page {
        String title = "";
        String content = "";
        String excerpt = "";
        int score;
    }

    private static final class RankedExcerpt {
        final String text;
        final int score;

        RankedExcerpt(String text, int score) {
            this.text = text == null ? "" : text;
            this.score = score;
        }
    }
}
