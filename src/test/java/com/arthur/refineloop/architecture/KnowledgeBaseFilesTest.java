package com.arthur.refineloop.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 《AI 知识库文件》的结构校验：{@code CLAUDE.md} + {@code .claude/rules/} + {@code .claude/skills/}。
 *
 * <p>为什么需要：这套文件是"给 AI 读的规范"，不是源码，改错了不会有编译错误——删掉一行 {@code paths:} 会让整篇规范
 * 每次会话全量注入；把链接写成已迁走的 {@code docs/开发规范/} 会让 AI 读到不存在的事实源；入口被拆成两份
 * 等于同一套红线两处维护。这些故障只能靠人肉评审发现，因此写成测试。
 *
 * <p>2026-11-25：规范正文迁入 {@code .claude/rules/}（只此一份）；{@code AGENTS.md} 已并入 {@code CLAUDE.md} 并删除。
 * 因此本类守两件事：知识库自身不自相矛盾，以及「第二份规范 / 第二个入口」不得重新长出来。
 *
 * <p>校验项：
 * <ol>
 *   <li>{@code CLAUDE.md} 是唯一 AI 入口：存在、≤ {@value #MAX_CLAUDE_MD_LINES} 行、含十条红线，且 {@code AGENTS.md} 不得复活；</li>
 *   <li>{@code .claude/rules/*.md} 必须带 {@code paths:} frontmatter（否则每次会话注入全部规范）、路径前缀真实存在、
 *       单文件 ≤ {@value #MAX_RULE_CHARS} 字符（Claude Code 对按需加载文件的截断阈值）、且被 {@code CLAUDE.md} 加载地图登记；</li>
 *   <li>规则覆盖后端 / 测试 / 前端 / 数据库四类路径（防止某条规则被整篇删掉）；</li>
 *   <li>知识库内引用的 {@code docs/**\/*.md}、{@code .claude/**\/*.md} 必须真实存在（防死链），
 *       且不得再引用已删除的 {@code docs/开发规范/}（防双份维护回潮）；</li>
 *   <li>{@code docs/开发规范/} 不得重新出现；</li>
 *   <li>每个 {@code .claude/skills/<name>/SKILL.md} 的 {@code name} 与目录同名且 {@code description} 非空。</li>
 * </ol>
 *
 * <p>本类只读文本，不解析 YAML：frontmatter 用行级规则解析，够用且无额外依赖。
 */
class KnowledgeBaseFilesTest {

    private static final Path CLAUDE_MD = Path.of("CLAUDE.md");
    /** 已并入 CLAUDE.md 的旧入口：不得复活，否则红线要维护两遍。 */
    private static final Path LEGACY_ENTRY = Path.of("AGENTS.md");
    private static final Path RULES_DIR = Path.of(".claude", "rules");
    private static final Path SKILLS_DIR = Path.of(".claude", "skills");
    /** 迁移前的规范目录：不得再出现，否则等于两份规范。 */
    private static final Path LEGACY_SPEC_DIR = Path.of("docs", "开发规范");

    private static final int MAX_CLAUDE_MD_LINES = 200;
    /** Claude Code 对按需加载（path-scoped）规则文件的上限约为 40k 字符，超过只会注入开头部分。 */
    private static final int MAX_RULE_CHARS = 40_000;

    /** 知识库里的文档引用：docs/技术架构与模块设计.md 这类。 */
    private static final Pattern DOC_REF = Pattern.compile("docs/[\\w\\p{IsHan}/.-]+\\.md");
    /** 知识库内部互相引用：.claude/rules/01-xxx.md、.claude/skills/tool/SKILL.md。 */
    private static final Pattern KNOWLEDGE_REF = Pattern.compile("\\.claude/(?:rules|skills)/[\\w\\p{IsHan}./-]+\\.md");
    /** 必须被规则覆盖的路径域（缺一个说明某条规则整篇被删或 paths 被改坏）。 */
    private static final List<String> REQUIRED_PATH_AREAS =
            List.of("src/main/java/", "src/test/", "frontend/", "db/");

    @Test
    @DisplayName("CLAUDE.md 是唯一 AI 入口：不超过 200 行，且 AGENTS.md 不得复活")
    void claudeMdIsTheSingleEntry() {
        assertThat(Files.exists(CLAUDE_MD))
                .as("缺 CLAUDE.md：AI 会话就没有入口，红线/命令/加载地图全都进不了上下文")
                .isTrue();
        assertThat(Files.exists(LEGACY_ENTRY))
                .as("AGENTS.md 又出现了：它已并入 CLAUDE.md，两份入口等于同一套红线维护两遍")
                .isFalse();

        List<String> lines = readLines(CLAUDE_MD);
        assertThat(String.join("\n", lines))
                .as("CLAUDE.md 必须自带十条红线（合并后它不再是导入壳）")
                .contains("十条红线");
        assertThat(lines.get(0).startsWith("@"))
                .as("CLAUDE.md 首行不应是 @ 导入：合并后它应当自包含")
                .isFalse();
        assertThat(lines.size())
                .as("CLAUDE.md 超过 %d 行会稀释遵守度，细节应留在 .claude/rules/", MAX_CLAUDE_MD_LINES)
                .isLessThanOrEqualTo(MAX_CLAUDE_MD_LINES);
    }

    @Test
    @DisplayName(".claude/rules：每篇都有 paths、路径真实、篇幅受控")
    void everyRuleIsPathScopedAndSized() {
        List<Path> rules = listMarkdown(RULES_DIR);
        assertThat(rules).as(".claude/rules/ 是规范的唯一载体，不能为空").isNotEmpty();

        for (Path rule : rules) {
            String name = fileName(rule);
            List<String> lines = readLines(rule);
            List<String> globs = frontmatterPaths(lines);

            assertThat(globs)
                    .as("%s 缺 paths frontmatter：没有 paths 的规则会在每次会话全量注入（本目录约 200KB）", name)
                    .isNotEmpty();
            for (String glob : globs) {
                assertThat(prefixExists(glob))
                        .as("%s 的 paths 条目 %s 指向的目录不存在（改目录后忘记同步规则）", name, glob)
                        .isTrue();
            }

            String text = String.join("\n", lines);
            assertThat(text.length())
                    .as("%s 超过 %d 字符：Claude Code 只会注入开头部分，需要拆分", name, MAX_RULE_CHARS)
                    .isLessThanOrEqualTo(MAX_RULE_CHARS);
        }
    }

    @Test
    @DisplayName(".claude/rules：覆盖后端 / 测试 / 前端 / 数据库四类路径")
    void rulesCoverTheMainCodeAreas() {
        Set<String> globs = new LinkedHashSet<>();
        for (Path rule : listMarkdown(RULES_DIR)) {
            globs.addAll(frontmatterPaths(readLines(rule)));
        }
        for (String area : REQUIRED_PATH_AREAS) {
            assertThat(globs.stream().anyMatch(glob -> glob.startsWith(area)))
                    .as("没有规则覆盖 %s：该目录下的改动将不在任何规范的自动加载范围内", area)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("知识库引用的文档与规则文件都真实存在（防死链）")
    void everyReferencePointsToAnExistingFile() {
        List<Path> sources = new ArrayList<>();
        sources.add(CLAUDE_MD);
        sources.addAll(listMarkdown(RULES_DIR));
        sources.addAll(skillFiles());

        for (Path source : sources) {
            String text = String.join("\n", readLines(source));
            for (Pattern pattern : List.of(DOC_REF, KNOWLEDGE_REF)) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String ref = matcher.group();
                    assertThat(Files.exists(Path.of(ref)))
                            .as("%s 引用了不存在的文件：%s（路径改了要同步知识库）", fileName(source), ref)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("规范只有一份：docs/开发规范 不得重新出现，知识库也不得再引用它")
    void specHasSingleHome() {
        assertThat(Files.exists(LEGACY_SPEC_DIR))
                .as("docs/开发规范/ 又出现了：规范正文只能放在 .claude/rules/，两份必然漂移")
                .isFalse();

        List<Path> sources = new ArrayList<>();
        sources.add(CLAUDE_MD);
        sources.addAll(listMarkdown(RULES_DIR));
        sources.addAll(skillFiles());
        for (Path source : sources) {
            assertThat(String.join("\n", readLines(source)))
                    .as("%s 仍在引用已迁走的 docs/开发规范/", fileName(source))
                    .doesNotContain("docs/开发规范/");
        }
    }

    @Test
    @DisplayName(".claude/skills：每个技能有 name（= 目录名）与 description")
    void everySkillHasNameAndDescription() {
        List<Path> skills = skillFiles();
        assertThat(skills).as(".claude/skills/ 不能为空").isNotEmpty();

        for (Path skill : skills) {
            List<String> lines = readLines(skill);
            String dirName = skill.getParent().getFileName().toString();
            String name = frontmatterValue(lines, "name");
            String description = frontmatterValue(lines, "description");

            assertThat(name).as("%s 缺 name 字段", dirName).isNotBlank();
            assertThat(name).as("%s 的 name 必须与目录同名（Claude Code 用它做调用名）", dirName).isEqualTo(dirName);
            assertThat(description)
                    .as("%s 缺 description：Claude 靠它判断何时调用这个技能", dirName)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("CLAUDE.md 的加载地图列出了全部规范文件")
    void claudeMdListsEveryRule() {
        String claude = String.join("\n", readLines(CLAUDE_MD));
        for (Path rule : listMarkdown(RULES_DIR)) {
            assertThat(claude)
                    .as("规范 %s 没有登记到 CLAUDE.md 的加载地图，AI 不会知道它被注入的条件", fileName(rule))
                    .contains(".claude/rules/" + fileName(rule));
        }
    }

    // ================================================================ 读文件

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取知识库文件失败: " + path, e);
        }
    }

    private static List<Path> listMarkdown(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列出知识库目录失败: " + dir, e);
        }
    }

    private static List<Path> skillFiles() {
        try (Stream<Path> stream = Files.walk(SKILLS_DIR, 3)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描技能目录失败: " + SKILLS_DIR, e);
        }
    }

    private static String fileName(Path path) {
        return path.getFileName().toString();
    }

    // ================================================================ 解析 frontmatter

    /** 解析 frontmatter 中 {@code paths:} 的列表项（去引号）。 */
    private static List<String> frontmatterPaths(List<String> lines) {
        List<String> paths = new ArrayList<>();
        int end = frontmatterEnd(lines);
        if (end < 0) {
            return paths;
        }
        boolean inPaths = false;
        for (int i = 1; i < end; i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("paths:")) {
                inPaths = true;
                continue;
            }
            if (inPaths && line.startsWith("-")) {
                String value = unquote(line.substring(1).strip());
                if (!value.isEmpty()) {
                    paths.add(value);
                }
                continue;
            }
            inPaths = false;
        }
        return paths;
    }

    /** 取 frontmatter 中某个标量字段的值，缺失返回空串。 */
    private static String frontmatterValue(List<String> lines, String key) {
        int end = frontmatterEnd(lines);
        for (int i = 1; i < end; i++) {
            String line = lines.get(i).strip();
            if (line.startsWith(key + ":")) {
                return unquote(line.substring(key.length() + 1).strip());
            }
        }
        return "";
    }

    private static int frontmatterEnd(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return -1;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** glob 的静态前缀目录是否存在；无通配符时按字面路径判断。 */
    private static boolean prefixExists(String glob) {
        int star = glob.indexOf('*');
        if (star < 0) {
            return Files.exists(Path.of(glob));
        }
        String prefix = glob.substring(0, star);
        int slash = prefix.lastIndexOf('/');
        String dir = slash < 0 ? prefix : prefix.substring(0, slash);
        return dir.isEmpty() || Files.exists(Path.of(dir));
    }
}
