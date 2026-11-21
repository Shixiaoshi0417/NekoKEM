// Release notes for the update prompt: the Markdown of a release turned into
// short plain-text lines. The release pages carry a Chinese and an English
// part ("## 简体中文", "## English"); only the part for the App's language is
// kept. Links, emphasis and code marks are dropped, so nothing in the notes is
// clickable. Code blocks, which hold checksums for the release page, are left
// out. Android has the same rules in ReleaseNotes.kt.
use serde::Serialize;

/// Notes beyond this many bytes are not read.
pub const MAX_INPUT_BYTES: usize = 64 * 1024;
pub const MAX_LINES: usize = 400;
pub const MAX_LINE_CHARS: usize = 2000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Kind {
    Heading,
    Item,
    Text,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NoteLine {
    pub kind: Kind,
    pub text: String,
}

fn bounded(text: &str) -> &str {
    if text.len() <= MAX_INPUT_BYTES {
        return text;
    }
    let mut end = MAX_INPUT_BYTES;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

/// The lines of the part for the language, the English part otherwise, or all lines without parts.
fn section<'a>(lines: &'a [&'a str], chinese: bool) -> &'a [&'a str] {
    let starts: Vec<usize> = (0..lines.len())
        .filter(|&i| lines[i].starts_with("## "))
        .collect();
    let Some(&first) = starts.first() else {
        return lines;
    };
    let find = |matches: &dyn Fn(&str) -> bool| {
        starts
            .iter()
            .copied()
            .find(|&i| matches(lines[i][3..].trim()))
    };
    let start = chinese
        .then(|| find(&|title| title.contains("中文")))
        .flatten()
        .or_else(|| find(&|title| title.eq_ignore_ascii_case("english")))
        .unwrap_or(first);
    let end = starts
        .iter()
        .copied()
        .find(|&i| i > start)
        .unwrap_or(lines.len());
    &lines[start + 1..end]
}

fn is_table_rule(line: &str) -> bool {
    line.starts_with('|')
        && line
            .chars()
            .all(|c| c == '|' || c == '-' || c == ':' || c.is_whitespace())
}

fn ordered_item(line: &str) -> Option<&str> {
    let digits = line.bytes().take_while(u8::is_ascii_digit).count();
    if digits == 0 || digits > 3 {
        return None;
    }
    let rest = line[digits..].strip_prefix(['.', ')'])?;
    rest.starts_with(char::is_whitespace)
        .then(|| rest.trim_start())
}

/// Keeps the text of links, drops images, emphasis and code marks.
fn inline(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(open) = rest.find('[') {
        let image = rest[..open].ends_with('!');
        let after = &rest[open + 1..];
        let Some(close) = after.find(']') else { break };
        let Some(target) = after[close + 1..].strip_prefix('(') else {
            out.push_str(&rest[..open + 1]);
            rest = after;
            continue;
        };
        let Some(end) = target.find(')') else { break };
        out.push_str(&rest[..if image { open - 1 } else { open }]);
        if !image {
            out.push_str(&after[..close]);
        }
        rest = &target[end + 1..];
    }
    out.push_str(rest);
    out.replace("**", "")
        .replace('`', "")
        .replace("<br>", " ")
        .trim()
        .to_owned()
}

pub fn lines(markdown: &str, chinese: bool) -> Vec<NoteLine> {
    let source = bounded(markdown).replace("\r\n", "\n").replace('\r', "\n");
    let all: Vec<&str> = source.lines().collect();
    let part = section(&all, chinese);
    let mut result = Vec::new();
    let mut code = false;
    for (index, raw) in part.iter().enumerate() {
        if result.len() >= MAX_LINES {
            break;
        }
        let line = raw.trim();
        if line.starts_with("```") || line.starts_with("~~~") {
            code = !code;
            continue;
        }
        if code
            || line.is_empty()
            || line.starts_with("<!--")
            || line.chars().all(|c| c == '-' || c == '*' || c == '_')
        {
            continue;
        }
        let next = part.get(index + 1).map_or("", |next| next.trim());
        let entry = if line.starts_with("# ") || is_table_rule(line) {
            None
        } else if line.starts_with('#') {
            Some((Kind::Heading, line.trim_start_matches('#').to_owned()))
        } else if let Some(item) = ["- ", "* ", "+ "]
            .iter()
            .find_map(|marker| line.strip_prefix(marker))
        {
            Some((Kind::Item, item.to_owned()))
        } else if let Some(item) = ordered_item(line) {
            Some((Kind::Item, item.to_owned()))
        } else if line.starts_with('|') && is_table_rule(next) {
            // A table's header row is the one above its rule.
            None
        } else if line.starts_with('|') {
            let cells: Vec<&str> = line.trim_matches('|').split('|').map(str::trim).collect();
            Some((Kind::Text, cells.join("  ")))
        } else {
            Some((Kind::Text, line.trim_start_matches('>').to_owned()))
        };
        let Some((kind, text)) = entry else { continue };
        let text: String = inline(&text).chars().take(MAX_LINE_CHARS).collect();
        if !text.is_empty() {
            result.push(NoteLine { kind, text });
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "# NekoKEM v9.9.9

## 简体中文

### 本版更新

本版加入 **新功能**。
- **记录原文件名**：见 [说明](https://example.invalid/)。
- 使用 `curl` 检查更新

| 平台 | 正式附件 |
|---|---|
| Android ARM64 | `app-release.apk` |

```text
0123abcd  app-release.apk
```

## English

### What's new

1. First
2) Second
---
> Quoted ![image](https://example.invalid/a.png)
";

    fn line(kind: Kind, text: &str) -> NoteLine {
        NoteLine {
            kind,
            text: text.into(),
        }
    }

    #[test]
    fn chinese_apps_read_the_chinese_part() {
        assert_eq!(
            lines(SAMPLE, true),
            vec![
                line(Kind::Heading, "本版更新"),
                line(Kind::Text, "本版加入 新功能。"),
                line(Kind::Item, "记录原文件名：见 说明。"),
                line(Kind::Item, "使用 curl 检查更新"),
                line(Kind::Text, "Android ARM64  app-release.apk"),
            ]
        );
    }

    #[test]
    fn other_apps_read_the_english_part() {
        assert_eq!(
            lines(SAMPLE, false),
            vec![
                line(Kind::Heading, "What's new"),
                line(Kind::Item, "First"),
                line(Kind::Item, "Second"),
                line(Kind::Text, "Quoted"),
            ]
        );
    }

    #[test]
    fn notes_without_parts_are_read_whole() {
        assert_eq!(
            lines("### Changes\r\n\r\n* One\r\n", true),
            vec![line(Kind::Heading, "Changes"), line(Kind::Item, "One")]
        );
        assert!(lines("", false).is_empty());
        assert_eq!(
            lines("## Deutsch\n- Eins\n## English\n- One", true),
            vec![line(Kind::Item, "One")]
        );
    }

    #[test]
    fn odd_markdown_is_kept_as_text() {
        assert_eq!(
            lines("- [a] b ![x\n- [c](d\n- 中文**粗**`码`", false),
            vec![
                line(Kind::Item, "[a] b ![x"),
                line(Kind::Item, "[c](d"),
                line(Kind::Item, "中文粗码"),
            ]
        );
    }

    #[test]
    fn long_notes_are_bounded() {
        let body = format!("- {}\n", "猫".repeat(5000)).repeat(1000);
        let parsed = lines(&body, false);
        assert!(parsed.len() <= MAX_LINES);
        assert!(parsed
            .iter()
            .all(|line| line.text.chars().count() <= MAX_LINE_CHARS));
    }
}
