// Package engine is a host-side verification harness for the Syncthing
// behaviour that Syncbox relies on. It is not the shipped bridge.
//
// The only non-test code is the ignore-line generator below, which the app's
// own generator mirrors.
package engine

import (
	"errors"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

// ErrUnrepresentable is returned for paths that no .stignore line can name
// exactly.
var ErrUnrepresentable = errors.New("path cannot be written as an ignore line")

// ErrSelectedUnderExcluded is returned when a selected path is equal to or
// inside an excluded path. Exclusion lines come first and win, so such a
// selection would be silently dropped.
var ErrSelectedUnderExcluded = errors.New("selected path is inside an excluded path")

// IncludeLine returns the .stignore line that un-ignores exactly the file or
// directory at path (and, for a directory, everything below it). The path is
// relative to the folder root and uses "/" as separator.
func IncludeLine(path string) (string, error) {
	p, err := RootedPattern(path)
	if err != nil {
		return "", err
	}
	return "!" + p, nil
}

// ExcludeLine returns the .stignore line that ignores exactly path. Placed
// before a broader include line it carves the path out of a selected folder,
// because the first matching line wins.
func ExcludeLine(path string) (string, error) {
	return RootedPattern(path)
}

// RootedPattern escapes path for Syncthing's ignore syntax on Unix and
// Android, where the escape character is a backslash, and roots it at the
// folder with a leading "/". The result:
//   - escapes the glob characters \ * ? [ ] { } with a backslash;
//   - is rooted, so a leading "#", "!" or "(?i)" in a name is never read as a
//     comment, negation or flag;
//   - writes trailing whitespace as one-character classes such as "[ ]",
//     because Syncthing trims every line with strings.TrimSpace. Whitespace
//     here is Go's unicode.IsSpace set, which is what TrimSpace uses; a
//     Kotlin port must use the same set (Char.isWhitespace, for example,
//     does not include U+0085);
//   - is NFC-normalized, which is what Syncthing does to patterns and file
//     names on every platform except Apple ones.
//
// Names containing a newline cannot be expressed and return
// ErrUnrepresentable, as do empty paths and paths with empty, "." or ".."
// components.
func RootedPattern(path string) (string, error) {
	path = strings.TrimPrefix(path, "/")
	if path == "" || strings.ContainsRune(path, '\n') || !utf8.ValidString(path) {
		return "", ErrUnrepresentable
	}
	for _, part := range strings.Split(path, "/") {
		if part == "" || part == "." || part == ".." {
			return "", ErrUnrepresentable
		}
	}
	path = norm.NFC.String(path)

	// Whitespace at the end of the line would be trimmed away; keep it by
	// wrapping each trailing whitespace character in a character class.
	body := strings.TrimRightFunc(path, unicode.IsSpace)
	tail := path[len(body):]

	var b strings.Builder
	b.WriteByte('/')
	for _, r := range body {
		switch r {
		case '\\', '*', '?', '[', ']', '{', '}':
			b.WriteByte('\\')
		}
		b.WriteRune(r)
	}
	for _, r := range tail {
		b.WriteByte('[')
		b.WriteRune(r)
		b.WriteByte(']')
	}
	return b.String(), nil
}

// WhitelistLines returns a complete .stignore for a receive-only folder:
// excluded paths first (so first-match-wins carves them out of a selected
// folder), then one include line per selected path, then, deepest first, two
// lines per ancestor directory of a selected path ("/A/**" then "!/A"), then
// "*".
//
// A selected path equal to or inside an excluded path is an error
// (ErrSelectedUnderExcluded): the caller must resolve it, for example by
// dropping the exclusion.
//
// The ancestor lines un-ignore each parent directory itself but none of its
// other children. Without them the puller creates the parent as a local
// directory with the phone's own version, flagged receive-only changed, and
// the other device counts it as needed forever (its GUI shows the phone at
// 95%). See TestNestedSelectionWithoutParentLines.
func WhitelistLines(selected, excluded []string) ([]string, error) {
	clean := func(ps []string) []string {
		out := make([]string, len(ps))
		for i, p := range ps {
			out[i] = norm.NFC.String(strings.Trim(p, "/"))
		}
		return out
	}
	cleanSel, cleanEx := clean(selected), clean(excluded)
	for _, s := range cleanSel {
		for _, e := range cleanEx {
			if s == e || strings.HasPrefix(s, e+"/") {
				return nil, ErrSelectedUnderExcluded
			}
		}
	}
	var lines []string
	for _, p := range excluded {
		l, err := ExcludeLine(p)
		if err != nil {
			return nil, err
		}
		lines = append(lines, l)
	}
	sel := map[string]bool{}
	for i, p := range selected {
		l, err := IncludeLine(p)
		if err != nil {
			return nil, err
		}
		lines = append(lines, l)
		sel[cleanSel[i]] = true
	}
	// Ancestors not already covered by a selected directory.
	anc := map[string]bool{}
	for p := range sel {
		for d := parent(p); d != ""; d = parent(d) {
			anc[d] = true
		}
	}
	var dirs []string
	for d := range anc {
		covered := false
		for a := d; a != "" && !covered; a = parent(a) {
			covered = sel[a]
		}
		if !covered {
			dirs = append(dirs, d)
		}
	}
	sort.Slice(dirs, func(i, j int) bool {
		di, dj := strings.Count(dirs[i], "/"), strings.Count(dirs[j], "/")
		if di != dj {
			return di > dj
		}
		return dirs[i] < dirs[j]
	})
	for _, d := range dirs {
		p, err := RootedPattern(d)
		if err != nil {
			return nil, err
		}
		lines = append(lines, p+"/**", "!"+p)
	}
	return append(lines, "*"), nil
}

func parent(p string) string {
	if i := strings.LastIndexByte(p, '/'); i > 0 {
		return p[:i]
	}
	return ""
}
