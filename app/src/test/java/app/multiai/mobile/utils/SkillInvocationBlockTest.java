package app.multiai.mobile.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.multiai.mobile.models.SkillPreset;

import org.junit.Test;

/**
 * Контракт system-блока триггера "./slug": БЕЗ тела Skill (full-body
 * системная инжекция удалена) и с указанием вызвать read_skill.
 */
public class SkillInvocationBlockTest {

    private static SkillPreset skill(String name, String slug, String description, String content) {
        SkillPreset skill = new SkillPreset();
        skill.name = name;
        skill.slug = slug;
        skill.description = description;
        skill.content = content;
        skill.enabled = true;
        return skill;
    }

    @Test
    public void blockDoesNotContainSkillBody() {
        String secretBody = "SECRET_INSTRUCTIONS_DO_NOT_INJECT";
        String block = SkillInvocation.renderSkillBlock(
                skill("Banner", "banner-design", "Makes banners", secretBody));
        assertFalse("renderSkillBlock must not inline the SKILL.md body into the system prompt",
                block.contains(secretBody));
    }

    @Test
    public void blockDirectsModelToReadSkillToolWithSlug() {
        String block = SkillInvocation.renderSkillBlock(
                skill("Banner", "banner-design", "Makes banners", "body"));
        assertTrue(block.contains("read_skill"));
        assertTrue(block.contains("banner-design"));
        // МеткаSkill: имя попадает, чтобы модель понимала, ЧТО вызвано.
        assertTrue(block.contains("Banner"));
    }

    @Test
    public void blockIncludesDescriptionForMatchContext() {
        String block = SkillInvocation.renderSkillBlock(
                skill("X", "x", "Design taste for visuals", "body"));
        assertTrue(block.contains("Design taste for visuals"));
    }

    @Test
    public void nullAndSluglessSkillsRenderNothing() {
        org.junit.Assert.assertEquals("", SkillInvocation.renderSkillBlock(null));
        SkillPreset slugless = skill("NoSlug", "", "d", "body");
        org.junit.Assert.assertEquals("", SkillInvocation.renderSkillBlock(slugless));
    }
}
