///usr/bin/env java --enable-preview --source 21 "$0" "$@"; exit $?
// ^ Shebang: run directly with `./GlossaryLinker.java --check README.md`
// Or: java tools/glossary-linker/GlossaryLinker.java --check README.md

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Glossary Auto-Linker — はてなキーワード的な自動リンク化 CLI.
 *
 * Usage:
 *   java GlossaryLinker.java --check README.md          # dry-run, show unlinked terms
 *   java GlossaryLinker.java --fix README.md             # auto-link in place
 *   java GlossaryLinker.java --check --lang ja README.ja.md
 *
 * Reads docs/glossary/*.md to build dictionary,
 * then scans target files for unlinked terms.
 */
public class GlossaryLinker {

    record Term(String display, String slug, String file, int priority) {}

    public static void main(String[] args) throws Exception {
        boolean fix = false;
        boolean check = false;
        String lang = "en";
        List<String> files = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--fix" -> fix = true;
                case "--check" -> check = true;
                case "--lang" -> lang = args[++i];
                default -> files.add(args[i]);
            }
        }

        if (!fix && !check) check = true; // default to check
        if (files.isEmpty()) {
            System.err.println("Usage: GlossaryLinker [--check|--fix] [--lang en|ja] <file>...");
            System.exit(1);
        }

        Path glossaryDir = Path.of("docs/glossary");
        if (!Files.isDirectory(glossaryDir)) {
            System.err.println("Glossary directory not found: " + glossaryDir);
            System.exit(1);
        }

        // Build dictionary from glossary files
        List<Term> dictionary = buildDictionary(glossaryDir, lang);
        // Sort by display length descending (longest match first)
        dictionary.sort((a, b) -> Integer.compare(b.display().length(), a.display().length()));

        System.out.println("Dictionary: " + dictionary.size() + " terms (" + lang + ")");

        int totalFound = 0;
        for (String file : files) {
            Path path = Path.of(file);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + path);
                continue;
            }
            String content = Files.readString(path);
            var result = linkContent(content, dictionary, lang);

            int found = result.linkCount;
            totalFound += found;

            if (check) {
                if (found > 0) {
                    System.out.println("\n" + file + ": " + found + " terms to link");
                    for (var entry : result.details) {
                        System.out.println("  + " + entry);
                    }
                } else {
                    System.out.println(file + ": all terms linked");
                }
            }

            if (fix && found > 0) {
                Files.writeString(path, result.content);
                System.out.println(file + ": " + found + " terms linked (written)");
            }
        }

        if (check) {
            System.out.println("\nTotal: " + totalFound + " unlinked terms found");
            if (totalFound > 0 && !fix) {
                System.exit(1); // CI failure
            }
        }
    }

    static List<Term> buildDictionary(Path glossaryDir, String lang) throws IOException {
        List<Term> terms = new ArrayList<>();
        String suffix = lang.equals("ja") ? ".ja.md" : ".md";

        try (var stream = Files.list(glossaryDir)) {
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (name.equals("README.md") || name.equals("dictionary.yaml")) continue;

                // Only process files matching the target language
                boolean isJa = name.endsWith(".ja.md");
                if (lang.equals("ja") && !isJa) continue;
                if (lang.equals("en") && isJa) continue;

                String slug = isJa ? name.replace(".ja.md", "") : name.replace(".md", "");
                String relativePath = "docs/glossary/" + name;

                // Read first line to get display name(s)
                List<String> lines = Files.readAllLines(file);
                if (lines.isEmpty()) continue;

                String title = lines.getFirst().replaceFirst("^#\\s+", "").trim();

                // Extract terms from title: "OIDC (OpenID Connect)" → ["OIDC", "OpenID Connect"]
                List<String> displays = extractDisplayTerms(title);

                for (String display : displays) {
                    if (display.length() < 2) continue; // skip single chars
                    terms.add(new Term(display, slug, relativePath, display.length()));
                }
            }
        }
        return terms;
    }

    static List<String> extractDisplayTerms(String title) {
        List<String> terms = new ArrayList<>();

        // Remove language links like "[日本語版はこちら](xxx)"
        title = title.replaceAll("\\[.*?\\]\\(.*?\\)", "").trim();

        // Split on " / " for bilingual titles
        for (String part : title.split("\\s*/\\s*")) {
            part = part.trim();
            if (part.isEmpty()) continue;

            // Handle parenthetical: "OIDC (OpenID Connect)" → "OIDC" + "OpenID Connect"
            Matcher m = Pattern.compile("^(.+?)\\s*\\((.+?)\\)$").matcher(part);
            if (m.matches()) {
                terms.add(m.group(1).trim());
                terms.add(m.group(2).trim());
            } else {
                terms.add(part);
            }
        }

        // Also add hyphenated→spaced variants: "multi-tenant" → "multi-tenant" (already there)
        return terms;
    }

    record LinkResult(String content, int linkCount, List<String> details) {}

    static LinkResult linkContent(String content, List<Term> dictionary, String lang) {
        String[] lines = content.split("\n", -1);
        int linkCount = 0;
        List<String> details = new ArrayList<>();
        Set<String> linkedInParagraph = new HashSet<>();
        boolean inCodeBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Toggle code block
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            if (inCodeBlock) continue;

            // Skip headings, table headers, HTML tags
            if (line.trim().startsWith("#")) continue;
            if (line.trim().startsWith("|") && line.contains("---")) continue;

            // Empty line resets paragraph dedup
            if (line.trim().isEmpty()) {
                linkedInParagraph.clear();
                continue;
            }

            // Try each term (longest first)
            for (Term term : dictionary) {
                if (linkedInParagraph.contains(term.slug())) continue;

                // Build regex: match term not already inside [...](...)
                // Negative lookbehind for [ and ( to avoid double-linking
                String escaped = Pattern.quote(term.display());
                // Case-insensitive for English, exact for Japanese
                int flags = isAscii(term.display()) ? Pattern.CASE_INSENSITIVE : 0;
                Pattern p = Pattern.compile(
                        "(?<!\\[)(?<!\\]\\()\\b" + escaped + "\\b(?![^\\[]*\\]\\()",
                        flags);

                Matcher m = p.matcher(lines[i]);
                if (m.find()) {
                    // Check if this occurrence is inside inline code `...`
                    String before = lines[i].substring(0, m.start());
                    long backticks = before.chars().filter(c -> c == '`').count();
                    if (backticks % 2 != 0) continue; // inside inline code

                    // Check if already linked (surrounded by [...](...)
                    if (isAlreadyLinked(lines[i], m.start(), m.end())) continue;

                    // Replace first occurrence only
                    String link = "[" + m.group() + "](" + term.file() + ")";
                    lines[i] = lines[i].substring(0, m.start()) + link + lines[i].substring(m.end());
                    linkedInParagraph.add(term.slug());
                    linkCount++;
                    details.add("L" + (i + 1) + ": " + term.display() + " → " + term.file());
                }
            }
        }

        return new LinkResult(String.join("\n", lines), linkCount, details);
    }

    static boolean isAlreadyLinked(String line, int start, int end) {
        // Check if the match is already part of [text](url)
        // Look backwards for '[' and forwards for '](url)'
        int bracketOpen = line.lastIndexOf('[', start);
        if (bracketOpen >= 0 && bracketOpen < start) {
            int bracketClose = line.indexOf("](", start);
            if (bracketClose >= 0 && bracketClose >= end - 1) {
                return true;
            }
        }
        return false;
    }

    static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }
}
