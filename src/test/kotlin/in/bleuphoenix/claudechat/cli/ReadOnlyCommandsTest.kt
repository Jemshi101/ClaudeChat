package `in`.bleuphoenix.claudechat.cli

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ReadOnlyCommandsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var home: File
    private lateinit var tmp: File

    @Before
    fun setUp() {
        home = temp.newFolder("home")
        root = File(home, "work/app").apply { mkdirs() }
        File(root, "src/main").mkdirs()
        File(root, "src/main/Main.kt").writeText("fun main() {}")
        File(root, "README.md").writeText("hello")
        File(home, "secret.txt").writeText("secret")
        File(home, ".claude/plans").mkdirs()
        File(home, ".claude/plans/my-plan.md").writeText("## Slice A")
        File(root, "package.json").writeText("{\"name\":\"app\"}")
        Files.createSymbolicLink(File(root, "escape").toPath(), home.toPath())
        tmp = temp.newFolder("tmp")
        File(tmp, "old.dart").writeText("old")
        // Anyone can plant links in a temp folder: writing through them lands elsewhere.
        Files.createSymbolicLink(File(tmp, "planted").toPath(), File(root, "README.md").toPath())
        Files.createSymbolicLink(File(tmp, "planted-dir").toPath(), home.toPath())
        Files.createSymbolicLink(File(tmp, "README.md").toPath(), File(home, "secret.txt").toPath())
        File(tmp, "sub").mkdirs()
    }

    private fun allowed(vararg commands: String) = commands.forEach {
        assertTrue("should run without asking: $it", ReadOnlyCommands.isReadOnlyInside(it, root, home, listOf(tmp)))
    }

    private fun asked(vararg commands: String) = commands.forEach {
        assertTrue("should ask: $it", !ReadOnlyCommands.isReadOnlyInside(it, root, home, listOf(tmp)))
    }

    @Test
    fun `copies into the temp folder run without asking`() = allowed(
        "cp src/main/Main.kt ${tmp.path}/_main_before.kt",
        "cp -p README.md ${tmp.path}/sub/readme.md",
        "cp -r src ${tmp.path}/src-copy",
        "cp src/main/Main.kt ${tmp.path}/old.dart",
        "cp src/main/Main.kt ${tmp.path}/sub",
        "cp \$(git ls-files src) ${tmp.path}/sub",
        "git show HEAD:src/main/Main.kt > ${tmp.path}/main_head.kt",
        "git diff >> ${tmp.path}/sub/changes.diff",
        "diff ${tmp.path}/old.dart src/main/Main.kt; cat ${tmp.path}/sub/x.kt",
        "cd src && cp main/Main.kt ${tmp.path}/m.kt && diff ${tmp.path}/m.kt main/Main.kt",
    )

    @Test
    fun `other copies ask`() = asked(
        "cp README.md copy.md",
        "cp README.md ${home.path}/x.md",
        "cp ~/secret.txt ${tmp.path}/s.txt",
        "cp README.md ${tmp.path}/planted",
        "cp README.md ${tmp.path}/planted-dir/x",
        "cp README.md ${tmp.path}",
        "cp README.md ${tmp.path}/*.md",
        "cp -t ${tmp.path} README.md",
        "cp -s README.md ${tmp.path}/l",
        "cp README.md",
        "cp README.md \$(cat README.md)",
        "echo hi > ${tmp.path}/planted",
        "echo hi > ${tmp.path}",
        "cat ${tmp.path}/README.md",
        "ls ${tmp.path}",
        "cd ${tmp.path} && ls",
    )

    @Test
    fun `reads inside the project run without asking`() = allowed(
        "ls -la",
        "cat README.md src/main/Main.kt",
        "grep -rn \"fun main\" src --include=*.kt",
        "grep -n -e '/api/v1' -A3 src/main/Main.kt",
        "rg -l TODO src | head -20",
        "sed -n '1,80p' src/main/Main.kt",
        "sed -n '/fun main/,/^}/p;200,220p' src/main/Main.kt",
        "head -50 README.md && wc -l src/main/*.kt",
        "find . -name '*.kt' -not -path './build/*' | sort | head",
        "cd src/main && grep -n main Main.kt",
        "cd ${root.path} && ls src",
        "git status --short && git log --oneline -10 && git diff HEAD~1 -- src",
        "git show HEAD:src/main/Main.kt | head -40",
        "git branch -a; git branch --show-current; git remote -v; git stash list",
        "git -C src log --format='%h %s' -5",
        "for f in src/main/Main.kt README.md; do echo \"== \$f\"; sed -n '1,5p' \"\$f\"; done",
        "F=src/main/Main.kt; grep -n main \$F; wc -l \"\${F}\"",
        "f=\$(grep -rl main src | head -1); sed -n '1,20p' \$f",
        "grep -rl main src | xargs grep -n fun",
        "find src -name '*.kt' -print0 | xargs -0 wc -l",
        "cat README.md 2>/dev/null || echo missing",
        "ls nothing-here 2>&1 | head",
        "awk '{print \$1}' README.md | sort | uniq -c",
        "jq '.name' package.json",
        "wc -l < README.md",
        "ls src/main/../main",
        "# just looking\nls",
        "cat <<EOF\nhi\nEOF",
        "for k in fun main; do c=\$(grep -E \"= '\$k'\" README.md | sed -E \"s/.*= ([a-z]+).*/\\1/\"); d=\$(grep -c \"x.\$c\\]\" README.md); echo \"\$k -> \$c default:\$d\"; done",
        "sed -E 's|^fun (\\w+).*|\\1|g; /^\$/d; \$!N; y/abc/xyz/' src/main/Main.kt",
        "sed -n '/fun/{p;q}' src/main/Main.kt | sed 's/fun/FUN/2'",
        "for r in src src/main; do echo \"== \$r (\$(git -C \$r branch --show-current))\"; git -C \$r log --oneline -3 | cat; done 2>&1 | head -90",
        "grep -q main README.md; echo exit=\$?",
        "timeout 300 flutter analyze src 2>&1 | tail -5",
        "echo \"changed: \$(git status --short | wc -l)\"",
        "python3 -I - src/main/Main.kt <<'EOF'\nimport sys, re, json\ntext = open(sys.argv[1], encoding='utf-8').read()\nprint(len(re.findall(r'fun ', text)), '/'.join(['a', 'b']))\nsys.stdout.write('done')\nEOF",
        "cd src && python3 - <<'PY'\nimport glob\nfor f in glob.glob('**/*.kt', recursive=True):\n    with open(f) as h:\n        print(f, len(h.read()))\nPY\necho ok",
        "python3 -c 'import json; print(json.load(open(\"package.json\"))[\"name\"])'",
        "python3 -c \"print('/api/v1/users'.split('/'))\"",
        "dart format --output=none --set-exit-if-changed src/main",
        "dart analyze src && flutter analyze",
        "sed -n '1,40p' ${home.path}/.claude/plans/my-plan.md",
        "grep -n Slice ~/.claude/plans/my-plan.md",
        "head -c 2000 ~/.claude/projects/-x/abc/tool-results/mcp-get_issue-1.txt",
    )

    @Test
    fun `reads outside the project ask`() = asked(
        "cat ~/secret.txt",
        "cat ../../secret.txt",
        "cat /etc/passwd",
        "ls \$HOME",
        "cd .. && cat secret.txt",
        "cd ${home.path} && ls",
        "cat escape/secret.txt",
        "grep -r password /",
        "grep -f /etc/passwd README.md",
        "find / -name '*.kt'",
        "git -C ${home.path} log",
        "git diff --no-index README.md /etc/hosts",
        "for f in /etc/hosts README.md; do cat \$f; done",
        "F=/etc/hosts; cat \$F",
        "echo /etc/passwd | xargs cat",
        "cat \$(cat README.md)",
        "ls ~",
        "cat ~root/x",
        "python3 -c 'print(open(\"/etc/hosts\").read())'",
        "python3 - <<'EOF'\nimport os\nprint(open(os.path.expanduser('~/.ssh/id_rsa')).read())\nEOF",
        "python3 - <<'EOF'\nprint(open('../../secret.txt').read())\nEOF",
        "python3 /etc/script.py",
        "cat ~/.claude/settings.json",
        "cat ~/.claude/projects/-x/abc/session.jsonl",
        "cat ~/.cursor/plans/x.md",
    )

    @Test
    fun `anything that writes or runs programs asks`() = asked(
        "echo hi > out.txt",
        "cat README.md >> notes.md",
        "cat README.md | tee copy.md",
        "sed -i '' 's/a/b/' README.md",
        "sed -n 's/a/b/w out' README.md",
        "sed -n '1p;w out' README.md",
        "sed -ni '1p' README.md",
        "sed 's/a/b/e' README.md",
        "sed 's/a/b/gw out' README.md",
        "sed '1r /etc/passwd' README.md",
        "sed 'R other' README.md",
        "sed '1e ls' README.md",
        "x=\$(cat README.md); sed \"\$x\" README.md",
        "find . -name '*.kt' -delete",
        "find . -exec cat {} \\;",
        "sort -o out README.md",
        "uniq README.md out",
        "awk '{print > \"out\"}' README.md",
        "awk 'BEGIN { system(\"rm -rf x\") }'",
        "rg --pre ./script x",
        "rm README.md",
        "mkdir x",
        "./gradlew test",
        "git checkout -- README.md",
        "git branch new-branch",
        "git stash",
        "git config user.name x",
        "git -c core.pager=evil log",
        "git diff --output=out",
        "git log > out",
        "PAGER=evil git log",
        "PATH=/tmp/evil; ls",
        "ls &",
        "(cd src && ls)",
        "cat `echo README.md`",
        "cat \$(echo README.md | sh)",
        "x=\$((1+1))",
        "if true; then ls; fi",
        "while read f; do cat \$f; done < README.md",
        "ls \$UNKNOWN_VAR",
        "curl https://example.com",
        "cat README.md | xargs rm",
        "ls | xargs cat",
        "python3 - <<'EOF'\nopen('out.txt', 'w').write('x')\nEOF",
        "python3 - <<'EOF'\nimport subprocess\nsubprocess.run(['rm', 'x'])\nEOF",
        "python3 - <<'EOF'\nimport pathlib\npathlib.Path('x').write_text('y')\nEOF",
        "python3 - <<'EOF'\nmode = 'w'\nopen('x', mode)\nEOF",
        "python3 - <<'EOF'\nimport os\nos.remove('x')\nEOF",
        "python3 - <<'EOF'\n__import__('os').system('ls')\nEOF",
        "python3 - <<'EOF'\nimport urllib.request\nurllib.request.urlopen('https://x')\nEOF",
        "python3 - <<EOF\nprint('\$HOME')\nEOF",
        "python3 -m http.server",
        "python3",
        "dart format src",
        "flutter test",
        "timeout 300 ./gradlew test",
        "cat \$?",
        "for r in src ..; do git -C \$r log; done",
        "flutter analyze --write=out.txt",
        "sed -i '' 's/a/b/' ~/.claude/plans/my-plan.md",
        "echo hi > ~/.claude/plans/my-plan.md",
    )

    @Test
    fun `file tools read the project, plans and the temp folder without asking`() {
        fun readable(path: String) = ReadOnlyCommands.isReadableInside(path, root, home, listOf(tmp))
        listOf(
            "${root.path}/README.md", "src/main/Main.kt", "${tmp.path}/design/04_AA170.png", "${tmp.path}/old.dart",
            "${tmp.path}/design/**/*.png", "~/.claude/plans/my-plan.md",
        ).forEach { assertTrue("should read: $it", readable(it)) }
        listOf(
            "${home.path}/secret.txt", "~/secret.txt", "/etc/hosts", "../../secret.txt", "${tmp.path}/README.md",
            "${tmp.path}", "${tmp.path}/**/*.png", "${root.path}/escape/secret.txt", "/", "",
        ).forEach { assertTrue("should ask: $it", !readable(it)) }
    }
}
