"""
Bedrock prompt templates for intelligent chapter detection
"""


def get_chapter_detection_prompt(markdown_content: str) -> str:
    """
    Generate structured prompt for Claude to detect chapters intelligently

    Args:
        markdown_content: Full book markdown text

    Returns:
        Formatted prompt string
    """
    prompt = f"""Analyze this book and identify all chapters with their content.

<document>
{markdown_content}
</document>

<instructions>
1. CHAPTER DETECTION - Intelligently identify chapter boundaries:
   - Look for variations: "Chapter 1", "Chapter One", "1.", "CHAPTER I", "Part One", "Section 1"
   - Detect numbered or titled sections that indicate new chapters
   - Consider semantic breaks in the narrative
   - DO NOT rely solely on markdown headers (# symbols)

2. EXCLUDE FRONT/BACK MATTER - Do not include:
   - Table of Contents
   - Copyright pages
   - Dedications, Acknowledgments
   - Preface, Foreword (unless it's a substantive chapter)
   - Index, Glossary
   - Appendices
   - "About the Author" sections
   - Bibliography

3. EXTRACT FULL CONTENT for each chapter:
   - Include ALL text from chapter start to chapter end
   - Preserve formatting and paragraph breaks
   - Include chapter title/number in the content

4. HANDLE EDGE CASES:
   - If no clear chapters found, treat entire book as one chapter
   - If only 1-2 chapters, that's okay (some books have few chapters)
   - Unnumbered chapters are okay (e.g., "Introduction", "Epilogue")

5. OUTPUT FORMAT - Return valid JSON only, no additional text:
</instructions>

Return ONLY valid JSON in this exact format:
{{
  "chapters": [
    {{
      "index": 1,
      "title": "Chapter One: Jonathan Harker's Journal",
      "content": "Full chapter text here, including all paragraphs..."
    }},
    {{
      "index": 2,
      "title": "Chapter Two: Continuing the Journey",
      "content": "Full chapter text here..."
    }}
  ]
}}

IMPORTANT: Return ONLY the JSON object. No preamble, no explanation, just the JSON."""

    return prompt


def get_fallback_chapter_split_prompt(markdown_content: str, target_word_count: int = 5000) -> str:
    """
    Fallback prompt for splitting book by word count when no chapters detected

    Args:
        markdown_content: Full book markdown text
        target_word_count: Target words per chunk

    Returns:
        Formatted prompt string
    """
    prompt = f"""Split this document into manageable chunks of approximately {target_word_count} words each.

<document>
{markdown_content}
</document>

<instructions>
1. Split at natural paragraph or section breaks
2. Each chunk should be ~{target_word_count} words (±500 words is acceptable)
3. Do not split mid-sentence
4. Preserve all content
5. Number chunks sequentially
</instructions>

Return ONLY valid JSON in this format:
{{
  "chapters": [
    {{
      "index": 1,
      "title": "Part 1",
      "content": "First chunk of approximately {target_word_count} words..."
    }},
    {{
      "index": 2,
      "title": "Part 2",
      "content": "Second chunk..."
    }}
  ]
}}"""

    return prompt
