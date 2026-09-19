package app.multiai.mobile.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Каталог голосов TTS. Два провайдера:
 *  - {@link #PROVIDER_EDGE} — Microsoft Edge TTS через бэкенд multiai.store.
 *  - {@link #PROVIDER_QWEN} — Qwen3 TTS напрямую через HF Gradio Space.
 *
 * Голоса портированы из Telegram-бота «Фроуз ии». Провайдер TeraTTS удалён.
 */
public final class VoiceCatalog {

    public static final String PROVIDER_EDGE = "edge";
    public static final String PROVIDER_QWEN = "qwen";

    public static final String DEFAULT_EDGE_VOICE = "ru-RU-SvetlanaNeural";
    public static final String DEFAULT_QWEN_VOICE = "Cherry / 芊悦";

    /** Один голос: провайдер + id для синтеза + отображаемое имя + подпись (пол · акцент). */
    public static final class Voice {
        public final String provider;
        public final String id;
        public final String display;
        public final String gender;   // "Женский" | "Мужской" | ""
        public final String accent;   // "Русский" | "Английский" | ... | ""

        Voice(String provider, String id, String display, String gender, String accent) {
            this.provider = provider;
            this.id = id;
            this.display = display;
            this.gender = gender == null ? "" : gender;
            this.accent = accent == null ? "" : accent;
        }

        /** Подзаголовок для строки списка: «Женский · Русский». */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (!gender.isEmpty()) sb.append(gender);
            if (!accent.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(accent);
            }
            return sb.toString();
        }

        /** Подпись в английской локали: gender · accent через английские переводы. */
        public String subtitle(boolean english) {
            if (!english) return subtitle();
            StringBuilder sb = new StringBuilder();
            if (!gender.isEmpty()) sb.append(mapGenderEn(gender));
            if (!accent.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(mapAccentEn(accent));
            }
            return sb.toString();
        }

        private static String mapGenderEn(String g) {
            if ("Мужской".equals(g)) return "Male";
            if ("Женский".equals(g)) return "Female";
            if ("Мультиязычный".equals(g)) return "Multilingual";
            return g;
        }

        private static String mapAccentEn(String a) {
            if (a == null || a.isEmpty()) return a;
            if (a.equals("Русский")) return "Russian";
            if (a.equals("Английский")) return "English";
            if (a.equals("Английский (AU)")) return "English (AU)";
            if (a.equals("Немецкий")) return "German";
            if (a.equals("Французский")) return "French";
            if (a.equals("Итальянский")) return "Italian";
            if (a.equals("Португальский")) return "Portuguese";
            if (a.equals("Корейский")) return "Korean";
            if (a.equals("Японский")) return "Japanese";
            if (a.equals("Испанский")) return "Spanish";
            return a;
        }

        /** Отображаемое имя голоса на нужном языке интерфейса. */
        public String displayName(boolean english) {
            if (!english) return display;
            String en = EN_DISPLAY.get(display);
            return en != null ? en : display;
        }

        private static final java.util.Map<String, String> EN_DISPLAY;
        static {
            java.util.Map<String, String> m = new java.util.HashMap<>();
            m.put("Светлана", "Svetlana");
            m.put("Дмитрий", "Dmitry");
            m.put("Ава", "Ava");
            m.put("Эмма", "Emma");
            m.put("Эндрю", "Andrew");
            m.put("Брайан", "Brian");
            m.put("Уильям", "William");
            m.put("Серафина", "Seraphina");
            m.put("Флориан", "Florian");
            m.put("Вивьен", "Vivienne");
            m.put("Реми", "Rémy");
            m.put("Джузеппе", "Giuseppe");
            m.put("Талита", "Thalita");
            m.put("Хёнсу", "Hyunsu");
            m.put("Черри", "Cherry");
            m.put("Серена", "Serena");
            m.put("Итан", "Ethan");
            m.put("Челси", "Chelsie");
            m.put("Момо", "Momo");
            m.put("Вивиан", "Vivian");
            m.put("Мун", "Moon");
            m.put("Майя", "Maia");
            m.put("Кай", "Kai");
            m.put("Нофиш", "Nofish");
            m.put("Белла", "Bella");
            m.put("Дженнифер", "Jennifer");
            m.put("Райан", "Ryan");
            m.put("Катерина", "Katerina");
            m.put("Эйден", "Aiden");
            m.put("Бодега", "Bodega");
            m.put("Алекс", "Alek");
            m.put("Дольче", "Dolce");
            m.put("Сохи", "Sohee");
            m.put("Онно Анна", "Ono Anna");
            m.put("Ленн", "Lenn");
            m.put("Сонриса", "Sonrisa");
            m.put("Эмильен", "Emilien");
            m.put("Андре", "André");
            m.put("Радио Гол", "Radio Gol");
            m.put("Элдрик Сейдж", "Eldric Sage");
            m.put("Миа", "Mia");
            m.put("Мочи", "Mochi");
            m.put("Беллона", "Bellona");
            m.put("Винсент", "Vincent");
            m.put("Банни", "Bunny");
            m.put("Нил", "Neil");
            m.put("Элиас", "Elias");
            m.put("Артур", "Arthur");
            m.put("Нини", "Nini");
            m.put("Эбона", "Ebona");
            m.put("Серен", "Seren");
            m.put("Пип", "Pip");
            m.put("Стелла", "Stella");
            m.put("Ли", "Li");
            m.put("Маркус", "Marcus");
            m.put("Рой", "Roy");
            m.put("Питер", "Peter");
            m.put("Эрик", "Eric");
            m.put("Рокки", "Rocky");
            m.put("Кики", "Kiki");
            m.put("Санни", "Sunny");
            m.put("Джада", "Jada");
            m.put("Дилан", "Dylan");
            EN_DISPLAY = java.util.Collections.unmodifiableMap(m);
        }

        /** Уникальная короткая фраза для предпрослушивания каждого голоса. */
        public String previewText() {
            switch (id) {
                // Edge TTS
                case "ru-RU-SvetlanaNeural":
                    return "Светлана в чате: давай превратим «не знаю с чего начать» в готовую имбу.";
                case "ru-RU-DmitryNeural":
                    return "Дмитрий на связи: кидай задачу, я уже разогреваю процессор мыслей.";
                case "en-US-AvaMultilingualNeural":
                    return "Ава залетела: если в коде шторм, соберём из него красивый корабль.";
                case "en-US-EmmaMultilingualNeural":
                    return "Эмма здесь: от фразы «ой всё» до «готово, работает» — один хороший план.";
                case "en-US-AndrewMultilingualNeural":
                    return "Эндрю в эфире: без паники, баги тоже иногда просто хотят внимания.";
                case "en-US-BrianMultilingualNeural":
                    return "Брайан подключился: берём хаос, добавляем логику, убираем кринж.";
                case "en-AU-WilliamMultilingualNeural":
                    return "Уильям на вайбе: давай сделаем штуку, которой самому захочется похвастаться.";
                case "de-DE-SeraphinaMultilingualNeural":
                    return "Серафина тут: идеи не кусаются, но я всё равно принесу им печеньки.";
                case "de-DE-FlorianMultilingualNeural":
                    return "Флориан на месте: собираю фокус по кусочкам, как редкий лут.";
                case "fr-FR-VivienneMultilingualNeural":
                    return "Вивьен врывается: сделаем аккуратно, стильно и без занудного квеста.";
                case "fr-FR-RemyMultilingualNeural":
                    return "Реми здесь: если задача выглядит страшно, делим её на куски и побеждаем.";
                case "it-IT-GiuseppeMultilingualNeural":
                    return "Джузеппе на связи: добавлю щепотку магии, но код всё равно будет честным.";
                case "pt-BR-ThalitaMultilingualNeural":
                    return "Талита готова к движу: код, мысли или умный рофл — выбирай режим.";
                case "ko-KR-HyunsuMultilingualNeural":
                    return "Хёнсу в деле: ловлю твою идею на лету и не даю ей улететь в черновики.";

                // Qwen3 TTS
                case "Cherry / 芊悦":
                    return "Черри тут: закинем идею в блендер и достанем сочный результат.";
                case "Serena / 苏瑶":
                    return "Серена на связи: сегодня даже сложная задача выглядит как лёгкий квест.";
                case "Ethan / 晨煦":
                    return "Итан в игре: собираю мысли в стройный отряд без лишнего шума.";
                case "Chelsie / 千雪":
                    return "Челси залетела: можно сделать быстро, красиво и не превратить всё в лапшу.";
                case "Momo / 茉兔":
                    return "Момо здесь: я маленькая, но план для большой идеи уже готов.";
                case "Vivian / 十三":
                    return "Вивиан на вайбе: берём твоё «а вдруг» и превращаем в «погнали».";
                case "Moon / 月白":
                    return "Мун в эфире: ночной режим мозга включён, давай собирать крутые штуки.";
                case "Maia / 四月":
                    return "Майя тут: добавлю свежий взгляд, чтобы идея зацвела, а не зависла.";
                case "Kai / 凯":
                    return "Кай подключился: коротко, чётко, по факту — и с нормальным вайбом.";
                case "Nofish / 不吃鱼":
                    return "Нофиш на месте: баги не ем, зато ловлю их быстрее, чем они прячутся.";
                case "Bella / 萌宝":
                    return "Белла в чате: давай сделаем так классно, чтобы даже черновик улыбнулся.";
                case "Jennifer / 詹妮弗":
                    return "Дженнифер здесь: спокойный голос, дерзкая идея и ни капли духоты.";
                case "Ryan / 甜茶":
                    return "Райан врывается: если есть задача, значит уже есть шанс сделать её легендой.";
                case "Katerina / 卡捷琳娜":
                    return "Катерина на связи: распутаем этот клубок так, что нитки сами удивятся.";
                case "Aiden / 艾登":
                    return "Эйден в деле: включаю режим «сначала ясно, потом красиво, потом вау».";
                case "Bodega / 西班牙语-博德加":
                    return "Бодега тут: добавим огонька в идею, но без пожара в проде.";
                case "Alek / 俄语-阿列克":
                    return "Алекс на связи: есть план, есть чай, осталось только разнести задачу.";
                case "Dolce / 意大利语-多尔切":
                    return "Дольче в эфире: сделаем мягко по подаче, жёстко по результату.";
                case "Sohee / 韩语-素熙":
                    return "Сохи здесь: ловлю детали, которые обычно прячутся за фразой «потом поправим».";
                case "Ono Anna / 日语-小野杏":
                    return "Онно Анна тут: маленький шаг сейчас — и огромный плюс к будущей версии.";
                case "Lenn / 德语-莱恩":
                    return "Ленн подключился: порядок в мыслях, скорость в деле, ноль лишней суеты.";
                case "Sonrisa / 西班牙语拉美-索尼莎":
                    return "Сонриса улыбается: давай добавим задаче цвета, а ответу — пользы.";
                case "Emilien / 法语-埃米尔安":
                    return "Эмильен на вайбе: превращаю задумки в маршрут, по которому приятно идти.";
                case "Andre / 葡萄牙语欧-安德雷":
                    return "Андре здесь: не обещаю магию, но очень похожий на неё результат — легко.";
                case "Radio Gol / 葡萄牙语巴-拉迪奥·戈尔":
                    return "Радио Гол в эфире: срочные новости — твоя идея только что стала мощнее.";
                case "Eldric Sage / 精品百人-沧明子":
                    return "Элдрик Сейдж говорит: древний свиток советует не бояться первого коммита.";
                case "Mia / 精品百人-乖小妹":
                    return "Миа тут: давай сделаем мило снаружи и очень умно внутри.";
                case "Mochi / 精品百人-沙小弥":
                    return "Мочи врывается: мягкий вайб, твёрдый план и ни одного скучного пункта.";
                case "Bellona / 精品百人-燕铮莺":
                    return "Беллона на связи: пора выдать задаче эпичный саундтрек и нормальное решение.";
                case "Vincent / 精品百人-田叔":
                    return "Винсент здесь: собираю серьёзный результат без серьёзного лица.";
                case "Bunny / 精品百人-萌小姬":
                    return "Банни прыгает в чат: сейчас разложим всё по полочкам, и будет мимими-ясно.";
                case "Neil / 精品百人-阿闻":
                    return "Нил в деле: меньше сомнений, больше тестов и один приятный финал.";
                case "Elias / 墨讲师":
                    return "Элиас тут: включаю режим умного объяснения, где никто не уснёт на втором слове.";
                case "Arthur / 精品百人-徐大爷":
                    return "Артур говорит: сто раз видел такие баги, и каждый раз они сдавались первыми.";
                case "Nini / 精品百人-邻家妹妹":
                    return "Нини на вайбе: давай из этой задумки слепим что-то реально любимое.";
                case "Ebona / 精品百人-诡婆婆":
                    return "Эбона шепчет: тёмный лес ошибок пройден, выход найден, фонарик заряжен.";
                case "Seren / 精品百人-小婉":
                    return "Серен здесь: добавлю спокойствия в хаос и искру в самый скучный таск.";
                case "Pip / 精品百人-调皮小新":
                    return "Пип врывается: давай устроим багам маленький, но очень заслуженный разнос.";
                case "Stella / 精品百人-美少女阿月":
                    return "Стелла на связи: зажигаю звёздный режим, но дедлайн всё равно помню.";
                case "Li / 南京-老李":
                    return "Ли тут: без лишних речей — берём инструмент, делаем дело, забираем победу.";
                case "Marcus / 陕西-秦川":
                    return "Маркус в эфире: если путь длинный, просто сделаем первый шаг по-королевски.";
                case "Roy / 闽南-阿杰":
                    return "Рой подключился: вытащим из этой мысли лучшее, остальное отправим в архив.";
                case "Peter / 天津-李彼得":
                    return "Питер здесь: задача принята, настроение боевое, кнопка «сдаться» не найдена.";
                case "Eric / 四川-程川":
                    return "Эрик на месте: ловлю суть быстрее, чем автодополнение успевает подсказать.";
                case "Rocky / 粤语-阿强":
                    return "Рокки в деле: поднимем эту идею с дивана и отправим покорять мир.";
                case "Kiki / 粤语-阿清":
                    return "Кики тут: сделаем легко на вид, хотя внутри будет чистая инженерная магия.";
                case "Sunny / 四川-晴儿":
                    return "Санни светит в чат: даже самый хмурый баг сегодня получит солнечный фикс.";
                case "Jada / 上海-阿珍":
                    return "Джада здесь: добавим задаче характера, а решению — тот самый хрустящий финал.";
                case "Dylan / 北京-晓东":
                    return "Дилан на связи: собираю режим турбо, но головой вперёд в стену не бежим.";
                default:
                    return "Я " + display + ". Кидай идею — соберём из неё что-то по-настоящему классное.";
            }
        }

        public String previewText(boolean english) {
            if (!english) return previewText();
            switch (id) {
                // Edge TTS
                case "ru-RU-SvetlanaNeural":
                    return "Svetlana here: let’s turn “I don’t know where to start” into a solid win.";
                case "ru-RU-DmitryNeural":
                    return "Dmitry on the line: drop the task, I’m already warming up my brain.";
                case "en-US-AvaMultilingualNeural":
                    return "Ava is in: even a code storm can be shaped into one beautiful ship.";
                case "en-US-EmmaMultilingualNeural":
                    return "Emma here: from “I give up” to “done, it works” — one good plan away.";
                case "en-US-AndrewMultilingualNeural":
                    return "Andrew on air: no panic, bugs sometimes just want a little attention.";
                case "en-US-BrianMultilingualNeural":
                    return "Brian connected: take the chaos, add logic, drop the cringe.";
                case "en-AU-WilliamMultilingualNeural":
                    return "William vibing: let’s build something you’ll want to show off.";
                case "de-DE-SeraphinaMultilingualNeural":
                    return "Seraphina here: ideas don’t bite, but I’ll bring cookies anyway.";
                case "de-DE-FlorianMultilingualNeural":
                    return "Florian on deck: gathering focus piece by piece, like a rare drop.";
                case "fr-FR-VivienneMultilingualNeural":
                    return "Vivienne is in: let’s do it neat, stylish, and without the boring quest.";
                case "fr-FR-RemyMultilingualNeural":
                    return "Remy here: if a task looks scary, we split it and win.";
                case "it-IT-GiuseppeMultilingualNeural":
                    return "Giuseppe on the line: a pinch of magic, but the code stays honest.";
                case "pt-BR-ThalitaMultilingualNeural":
                    return "Thalita ready for action: code, ideas, or a clever move — pick a mode.";
                case "ko-KR-HyunsuMultilingualNeural":
                    return "Hyunsu on it: catching your idea mid-air so it never lands in drafts.";

                // Qwen3 TTS
                case "Cherry / 芊悦":
                    return "Cherry here: let’s toss your idea in the blender and pull out something juicy.";
                case "Serena / 苏瑶":
                    return "Serena on the line: even a tricky task feels like an easy quest today.";
                case "Ethan / 晨煦":
                    return "Ethan in the game: lining up the thoughts into a tight crew, no extra noise.";
                case "Chelsie / 千雪":
                    return "Chelsie dropped in: we can go fast, clean, and not turn it into spaghetti.";
                case "Momo / 茉兔":
                    return "Momo here: I’m small, but the plan for your big idea is already ready.";
                case "Vivian / 十三":
                    return "Vivian vibing: taking your “what if” and turning it into “let’s go”.";
                case "Moon / 月白":
                    return "Moon on air: night-brain mode is on, let’s build something cool.";
                case "Maia / 四月":
                    return "Maia here: adding a fresh take so your idea blooms instead of stalling.";
                case "Kai / 凯":
                    return "Kai connected: short, sharp, to the point — and with a good vibe.";
                case "Nofish / 不吃鱼":
                    return "Nofish on deck: I don’t eat bugs, but I catch them faster than they hide.";
                case "Bella / 萌宝":
                    return "Bella in chat: let’s make it so good even the draft smiles.";
                case "Jennifer / 詹妮弗":
                    return "Jennifer here: calm voice, bold idea, zero stuffiness.";
                case "Ryan / 甜茶":
                    return "Ryan is in: if there’s a task, there’s already a shot at making it legendary.";
                case "Katerina / 卡捷琳娜":
                    return "Katerina on the line: untangling this knot so even the threads get surprised.";
                case "Aiden / 艾登":
                    return "Aiden on it: first clear, then beautiful, then wow.";
                case "Bodega / 西班牙语-博德加":
                    return "Bodega here: adding some fire to the idea, but no production fire.";
                case "Alek / 俄语-阿列克":
                    return "Alek on the line: there’s a plan, there’s tea, we just need to crush the task.";
                case "Dolce / 意大利语-多尔切":
                    return "Dolce on air: smooth on the delivery, hard on the results.";
                case "Sohee / 韩语-素熙":
                    return "Sohee here: catching the details that usually hide behind “I’ll fix it later”.";
                case "Ono Anna / 日语-小野杏":
                    return "Ono Anna here: a small step now — a big plus for the next version.";
                case "Lenn / 德语-莱恩":
                    return "Lenn connected: order in thoughts, speed in work, zero fuss.";
                case "Sonrisa / 西班牙语拉美-索尼莎":
                    return "Sonrisa smiles: let’s add some color to the task and value to the answer.";
                case "Emilien / 法语-埃米尔安":
                    return "Emilien vibing: turning your ideas into a route that’s nice to follow.";
                case "Andre / 葡萄牙语欧-安德雷":
                    return "Andre here: no magic promised, but something very close to it — easily.";
                case "Radio Gol / 葡萄牙语巴-拉迪奥·戈尔":
                    return "Radio Gol on air: breaking news — your idea just got stronger.";
                case "Eldric Sage / 精品百人-沧明子":
                    return "Eldric Sage says: the ancient scroll advises not to fear the first commit.";
                case "Mia / 精品百人-乖小妹":
                    return "Mia here: let’s make it cute on the outside and very smart on the inside.";
                case "Mochi / 精品百人-沙小弥":
                    return "Mochi is in: soft vibe, solid plan, and not a single dull point.";
                case "Bellona / 精品百人-燕铮莺":
                    return "Bellona on the line: time to give the task an epic soundtrack and a real fix.";
                case "Vincent / 精品百人-田叔":
                    return "Vincent here: building a serious result without a serious face.";
                case "Bunny / 精品百人-萌小姬":
                    return "Bunny hops in: let’s sort it all out, and it’ll be clear in a snap.";
                case "Neil / 精品百人-阿闻":
                    return "Neil on it: fewer doubts, more tests, and one pleasant finish.";
                case "Elias / 墨讲师":
                    return "Elias here: smart-explainer mode is on, no one falls asleep on the second word.";
                case "Arthur / 精品百人-徐大爷":
                    return "Arthur says: I’ve seen bugs like these a hundred times, and they always give up.";
                case "Nini / 精品百人-邻家妹妹":
                    return "Nini vibing: let’s shape this idea into something you’ll actually love.";
                case "Ebona / 精品百人-诡婆婆":
                    return "Ebona whispers: the dark bug forest is cleared, the exit is found, the light is on.";
                case "Seren / 精品百人-小婉":
                    return "Seren here: adding calm to the chaos and a spark to the dullest task.";
                case "Pip / 精品百人-调皮小新":
                    return "Pip is in: let’s give the bugs a small but very deserved beatdown.";
                case "Stella / 精品百人-美少女阿月":
                    return "Stella on the line: turning on star mode, but I still remember the deadline.";
                case "Li / 南京-老李":
                    return "Li here: no small talk — grab the tool, do the work, take the win.";
                case "Marcus / 陕西-秦川":
                    return "Marcus on air: if the road is long, we just take the first step in style.";
                case "Roy / 闽南-阿杰":
                    return "Roy connected: pulling the best out of this thought, archiving the rest.";
                case "Peter / 天津-李彼得":
                    return "Peter here: task accepted, mood is up, and the “give up” button is gone.";
                case "Eric / 四川-程川":
                    return "Eric on deck: catching the point faster than autocomplete can suggest.";
                case "Rocky / 粤语-阿强":
                    return "Rocky on it: getting this idea off the couch and out to conquer the world.";
                case "Kiki / 粤语-阿清":
                    return "Kiki here: making it look easy, while it’s pure engineering magic inside.";
                case "Sunny / 四川-晴儿":
                    return "Sunny shines in: even the grumpiest bug gets a sunny fix today.";
                case "Jada / 上海-阿珍":
                    return "Jada here: giving the task some character and the fix that crisp finish.";
                case "Dylan / 北京-晓东":
                    return "Dylan on the line: turbo mode is on, but we don’t run headfirst into walls.";
                default:
                    return "This is " + displayName(true) + ". Share your idea — let’s turn it into something truly great.";
            }
        }
    }

    public static String providerName(String provider) {
        if (PROVIDER_QWEN.equals(provider)) return "Qwen3 TTS";
        return "Edge TTS";
    }

    public static List<Voice> forProvider(String provider) {
        return PROVIDER_QWEN.equals(provider) ? qwen() : edge();
    }

    /** Все голоса подряд (Edge затем Qwen) — для общего списка. */
    public static List<Voice> all() {
        List<Voice> list = new ArrayList<>();
        list.addAll(edge());
        list.addAll(qwen());
        return list;
    }

    public static Voice find(String provider, String id) {
        if (id == null) return null;
        for (Voice v : forProvider(provider)) {
            if (v.id.equals(id)) return v;
        }
        return null;
    }

    /** Валидный ли (провайдер, голос). Иначе UI должен откатиться на дефолт. */
    public static boolean isValid(String provider, String id) {
        return find(provider, id) != null;
    }

    // ─────────────────────────── EDGE ───────────────────────────

    public static List<Voice> edge() {
        List<Voice> v = new ArrayList<>();
        v.add(new Voice(PROVIDER_EDGE, "ru-RU-SvetlanaNeural", "Светлана", "Женский", "Русский"));
        v.add(new Voice(PROVIDER_EDGE, "ru-RU-DmitryNeural", "Дмитрий", "Мужской", "Русский"));
        v.add(new Voice(PROVIDER_EDGE, "en-US-AvaMultilingualNeural", "Ава", "Женский", "Английский"));
        v.add(new Voice(PROVIDER_EDGE, "en-US-EmmaMultilingualNeural", "Эмма", "Женский", "Английский"));
        v.add(new Voice(PROVIDER_EDGE, "en-US-AndrewMultilingualNeural", "Эндрю", "Мужской", "Английский"));
        v.add(new Voice(PROVIDER_EDGE, "en-US-BrianMultilingualNeural", "Брайан", "Мужской", "Английский"));
        v.add(new Voice(PROVIDER_EDGE, "en-AU-WilliamMultilingualNeural", "Уильям", "Мужской", "Английский (AU)"));
        v.add(new Voice(PROVIDER_EDGE, "de-DE-SeraphinaMultilingualNeural", "Серафина", "Женский", "Немецкий"));
        v.add(new Voice(PROVIDER_EDGE, "de-DE-FlorianMultilingualNeural", "Флориан", "Мужской", "Немецкий"));
        v.add(new Voice(PROVIDER_EDGE, "fr-FR-VivienneMultilingualNeural", "Вивьен", "Женский", "Французский"));
        v.add(new Voice(PROVIDER_EDGE, "fr-FR-RemyMultilingualNeural", "Реми", "Мужской", "Французский"));
        v.add(new Voice(PROVIDER_EDGE, "it-IT-GiuseppeMultilingualNeural", "Джузеппе", "Мужской", "Итальянский"));
        v.add(new Voice(PROVIDER_EDGE, "pt-BR-ThalitaMultilingualNeural", "Талита", "Женский", "Португальский"));
        v.add(new Voice(PROVIDER_EDGE, "ko-KR-HyunsuMultilingualNeural", "Хёнсу", "Мужской", "Корейский"));
        return v;
    }

    // ─────────────────────────── QWEN3 ───────────────────────────

    private static Voice q(String id, String display, String gender, String accent) {
        return new Voice(PROVIDER_QWEN, id, display, gender, accent);
    }

    public static List<Voice> qwen() {
        List<Voice> v = new ArrayList<>();
        v.add(q("Cherry / 芊悦", "Cherry", "Женский", ""));
        v.add(q("Serena / 苏瑶", "Serena", "Женский", ""));
        v.add(q("Ethan / 晨煦", "Ethan", "Мужской", ""));
        v.add(q("Chelsie / 千雪", "Chelsie", "Женский", ""));
        v.add(q("Momo / 茉兔", "Momo", "Женский", ""));
        v.add(q("Vivian / 十三", "Vivian", "Женский", ""));
        v.add(q("Moon / 月白", "Moon", "Мужской", ""));
        v.add(q("Maia / 四月", "Maia", "Женский", ""));
        v.add(q("Kai / 凯", "Kai", "Мужской", ""));
        v.add(q("Nofish / 不吃鱼", "Nofish", "Мужской", ""));
        v.add(q("Bella / 萌宝", "Bella", "Женский", ""));
        v.add(q("Jennifer / 詹妮弗", "Jennifer", "Женский", ""));
        v.add(q("Ryan / 甜茶", "Ryan", "Мужской", ""));
        v.add(q("Katerina / 卡捷琳娜", "Катерина", "Женский", "Русский"));
        v.add(q("Aiden / 艾登", "Aiden", "Мужской", ""));
        v.add(q("Bodega / 西班牙语-博德加", "Бодега", "Мужской", "Испанский"));
        v.add(q("Alek / 俄语-阿列克", "Алекс", "Мужской", "Русский"));
        v.add(q("Dolce / 意大利语-多尔切", "Дольче", "Мужской", "Итальянский"));
        v.add(q("Sohee / 韩语-素熙", "Сохи", "Женский", "Корейский"));
        v.add(q("Ono Anna / 日语-小野杏", "Онно Анна", "Женский", "Японский"));
        v.add(q("Lenn / 德语-莱恩", "Ленн", "Мужской", "Немецкий"));
        v.add(q("Sonrisa / 西班牙语拉美-索尼莎", "Сонриса", "Женский", "Испанский"));
        v.add(q("Emilien / 法语-埃米尔安", "Эмильен", "Мужской", "Французский"));
        v.add(q("Andre / 葡萄牙语欧-安德雷", "Андре", "Мужской", "Португальский"));
        v.add(q("Radio Gol / 葡萄牙语巴-拉迪奥·戈尔", "Радио Гол", "Мужской", "Португальский"));
        v.add(q("Eldric Sage / 精品百人-沧明子", "Элдрик Сейдж", "Мужской", ""));
        v.add(q("Mia / 精品百人-乖小妹", "Миа", "Женский", ""));
        v.add(q("Mochi / 精品百人-沙小弥", "Мочи", "Женский", ""));
        v.add(q("Bellona / 精品百人-燕铮莺", "Беллона", "Женский", ""));
        v.add(q("Vincent / 精品百人-田叔", "Винсент", "Мужской", ""));
        v.add(q("Bunny / 精品百人-萌小姬", "Банни", "Женский", ""));
        v.add(q("Neil / 精品百人-阿闻", "Нил", "Мужской", ""));
        v.add(q("Elias / 墨讲师", "Элиас", "Женский", ""));
        v.add(q("Arthur / 精品百人-徐大爷", "Артур", "Мужской", ""));
        v.add(q("Nini / 精品百人-邻家妹妹", "Нини", "Женский", ""));
        v.add(q("Ebona / 精品百人-诡婆婆", "Эбона", "Женский", ""));
        v.add(q("Seren / 精品百人-小婉", "Серен", "Женский", ""));
        v.add(q("Pip / 精品百人-调皮小新", "Пип", "Женский", ""));
        v.add(q("Stella / 精品百人-美少女阿月", "Стелла", "Женский", ""));
        v.add(q("Li / 南京-老李", "Ли", "Мужской", ""));
        v.add(q("Marcus / 陕西-秦川", "Маркус", "Мужской", ""));
        v.add(q("Roy / 闽南-阿杰", "Рой", "Мужской", ""));
        v.add(q("Peter / 天津-李彼得", "Питер", "Мужской", ""));
        v.add(q("Eric / 四川-程川", "Эрик", "Мужской", ""));
        v.add(q("Rocky / 粤语-阿强", "Рокки", "Мужской", ""));
        v.add(q("Kiki / 粤语-阿清", "Кики", "Женский", ""));
        v.add(q("Sunny / 四川-晴儿", "Санни", "Женский", ""));
        v.add(q("Jada / 上海-阿珍", "Джада", "Женский", ""));
        v.add(q("Dylan / 北京-晓东", "Дилан", "Мужской", ""));
        return Collections.unmodifiableList(v);
    }

    private VoiceCatalog() {}
}
