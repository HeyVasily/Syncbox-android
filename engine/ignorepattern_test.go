package engine

import (
	"errors"
	"strings"
	"testing"

	"github.com/syncthing/syncthing/lib/fs"
	"github.com/syncthing/syncthing/lib/ignore"
	"golang.org/x/text/unicode/norm"
)

// specialNames are file names that a naive generator gets wrong. Each one is
// paired with decoys that a wrong pattern would also match.
var specialNames = []struct {
	name   string
	decoys []string
}{
	{`back\slash`, []string{`backslash`, `back/slash`}},
	{`star*`, []string{`star`, `starry`}},
	{`what?`, []string{`whatX`, `what`}},
	{`[ab]`, []string{`a`, `b`, `[ab`}},
	{`{c,d}`, []string{`c`, `d`, `{c`}},
	{`#hash`, []string{`hash`}},
	{`#include x`, []string{`x`}},
	{`!bang`, []string{`bang`}},
	{`(?i)Flag`, []string{`(?i)flag`, `flag`}},
	{`trail `, []string{`trail`, `trail  `}},
	{"tab\t", []string{"tab", "tab "}},
	{` lead`, []string{`lead`}},
	{`dir /file`, []string{`dir/file`}},
	{norm.NFC.String("café"), []string{"cafe"}},
	{"\u03b1\u03b2\u03b3 \u65e5\u672c", []string{"x"}},
	{`a,b`, []string{`a`}},
	{`**`, []string{`x`, `x/y`}},
	{`(?d)del`, []string{`del`}},
	{`#escape=x`, []string{`x`}},
	{"cr\rmid", []string{"cr", "crmid"}},
	{"cr\r", []string{"cr"}},
	{"   ", []string{" ", "  ", "x"}},
	{"nel\u0085", []string{"nel"}},
}

func newMatcher(t *testing.T, lines ...string) *ignore.Matcher {
	t.Helper()
	m := ignore.New(fs.NewFilesystem(fs.FilesystemTypeBasic, t.TempDir()))
	if err := m.Parse(strings.NewReader(strings.Join(lines, "\n")+"\n"), ".stignore"); err != nil {
		t.Fatalf("parse %q: %v", lines, err)
	}
	return m
}

func TestIncludeLineMatchesExactlyOneName(t *testing.T) {
	for _, tc := range specialNames {
		line, err := IncludeLine(tc.name)
		if err != nil {
			t.Fatalf("IncludeLine(%q): %v", tc.name, err)
		}
		m := newMatcher(t, line, "*")
		if m.Match(tc.name).IsIgnored() {
			t.Errorf("%q: line %q does not select the name", tc.name, line)
		}
		if m.Match(tc.name + "/child").IsIgnored() {
			t.Errorf("%q: line %q does not select children of a directory", tc.name, line)
		}
		for _, d := range tc.decoys {
			if !m.Match(d).IsIgnored() {
				t.Errorf("%q: line %q also selects decoy %q", tc.name, line, d)
			}
		}
	}
}

func TestExcludeLineCarvesOutOfSelectedFolder(t *testing.T) {
	for _, tc := range specialNames {
		path := "Movies/" + tc.name
		ex, err := ExcludeLine(path)
		if err != nil {
			t.Fatal(err)
		}
		in, _ := IncludeLine("Movies")
		m := newMatcher(t, ex, in, "*")
		if !m.Match(path).IsIgnored() {
			t.Errorf("%q: exclude line %q does not exclude", tc.name, ex)
		}
		for _, d := range tc.decoys {
			if m.Match("Movies/" + d).IsIgnored() {
				t.Errorf("%q: exclude line %q also excludes sibling %q", tc.name, ex, d)
			}
		}
	}
}

func TestPatternIsNFCAndMatchesNFDInput(t *testing.T) {
	nfd := norm.NFD.String("café")
	line, err := IncludeLine(nfd)
	if err != nil {
		t.Fatal(err)
	}
	if !norm.NFC.IsNormalString(line) {
		t.Fatalf("line %q is not NFC", line)
	}
	m := newMatcher(t, line, "*")
	// Syncthing on Linux and Android stores and matches NFC names.
	if m.Match(norm.NFC.String(nfd)).IsIgnored() {
		t.Fatalf("NFC name not selected by %q", line)
	}
	// Syncthing also normalizes the pattern itself, so a raw NFD line works.
	m = newMatcher(t, "!/"+nfd, "*")
	if m.Match(norm.NFC.String(nfd)).IsIgnored() {
		t.Fatalf("raw NFD line does not select the NFC name")
	}
}

func TestTrailingWhitespaceIsKept(t *testing.T) {
	line, _ := IncludeLine("a  ")
	if line != "!/a[ ][ ]" {
		t.Fatalf("got %q", line)
	}
	// Without the encoding Syncthing trims the line and selects the wrong file.
	m := newMatcher(t, "!/a  ", "*")
	if !m.Match("a  ").IsIgnored() || m.Match("a").IsIgnored() {
		t.Fatalf("expected the raw line to be trimmed to %q", "!/a")
	}
}

func TestUnrepresentablePaths(t *testing.T) {
	for _, p := range []string{"", "/", "a\nb", "a//b", "a/./b", "../a", "bad\xff"} {
		if _, err := IncludeLine(p); !errors.Is(err, ErrUnrepresentable) {
			t.Errorf("IncludeLine(%q) = %v, want ErrUnrepresentable", p, err)
		}
	}
}

func TestEscapingExamples(t *testing.T) {
	cases := map[string]string{
		`Movies`:           `!/Movies`,
		`a\b*c?d[e]f{g}`:   `!/a\\b\*c\?d\[e\]f\{g\}`,
		`#x`:               `!/#x`,
		`dir/sub file.mkv`: `!/dir/sub file.mkv`,
	}
	for in, want := range cases {
		got, err := IncludeLine(in)
		if err != nil || got != want {
			t.Errorf("IncludeLine(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
}

func TestWhitelistLines(t *testing.T) {
	lines, err := WhitelistLines(
		[]string{"Movies", "Shows/S1/e1.mkv", "Shows/S2 /e2.mkv", "Movies/sub/x.mkv"},
		[]string{"Movies/skip.mkv"},
	)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"/Movies/skip.mkv",
		"!/Movies", "!/Shows/S1/e1.mkv", "!/Shows/S2 /e2.mkv", "!/Movies/sub/x.mkv",
		"/Shows/S1/**", "!/Shows/S1", "/Shows/S2[ ]/**", "!/Shows/S2[ ]", "/Shows/**", "!/Shows",
		"*",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") {
		t.Fatalf("got\n%s\nwant\n%s", strings.Join(lines, "\n"), strings.Join(want, "\n"))
	}
	m := newMatcher(t, lines...)
	selected := []string{
		"Movies", "Movies/a.mkv", "Movies/new/b.mkv", "Movies/sub/x.mkv",
		"Shows", "Shows/S1", "Shows/S1/e1.mkv", "Shows/S2 ", "Shows/S2 /e2.mkv",
	}
	ignored := []string{
		"Movies/skip.mkv", "Shows/S1/e2.mkv", "Shows/S3", "Shows/S3/e1.mkv", "Shows/S2 /e3.mkv",
		"Shows/S2", "Shows/x.mkv", "Other", "Other/Movies", "Movies2",
	}
	for _, p := range selected {
		if m.Match(p).IsIgnored() {
			t.Errorf("%q is ignored, want selected", p)
		}
	}
	for _, p := range ignored {
		if !m.Match(p).IsIgnored() {
			t.Errorf("%q is selected, want ignored", p)
		}
	}
}

func TestWhitelistLinesEmptySelection(t *testing.T) {
	lines, err := WhitelistLines(nil, nil)
	if err != nil || len(lines) != 1 || lines[0] != "*" {
		t.Fatalf("got %q, %v", lines, err)
	}
}

func TestSelectedUnderExcludedIsAnError(t *testing.T) {
	for _, sel := range []string{"Movies/skip", "Movies/skip/a.mkv", "/Movies/skip/"} {
		if _, err := WhitelistLines([]string{sel}, []string{"Movies/skip"}); !errors.Is(err, ErrSelectedUnderExcluded) {
			t.Errorf("selected %q under excluded Movies/skip: err = %v", sel, err)
		}
	}
	if _, err := WhitelistLines([]string{"Movies/skipped"}, []string{"Movies/skip"}); err != nil {
		t.Errorf("sibling with a common prefix rejected: %v", err)
	}
}
