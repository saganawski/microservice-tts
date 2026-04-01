"""
Emotion Tagger — Analyzes text and inserts Orpheus emotion tags.

Works as both an importable library (for TTS generation lambda) and
as part of the emotion-preprocessing Lambda.

Supported tags:
  Primary (7):   <laugh>, <sigh>, <cough>, <groan>, <yawn>, <gasp>, <scream>
  Secondary (4): <chuckle>, <giggle>, <moan>, <sad>
Emotion levels: none (pass-through), subtle (light tagging), dramatic (aggressive tagging)

Usage as library:
    from emotion_tagger import EmotionTagger
    tagger = EmotionTagger(api_key="...", level="subtle")
    tagged_text = tagger.tag(text)
"""

import json
import logging
import os
import re

from google import genai

logger = logging.getLogger(__name__)

ORPHEUS_EMOTION_TAGS = [
    # Primary (confirmed working)
    "<laugh>", "<sigh>", "<cough>", "<groan>", "<yawn>", "<gasp>", "<scream>",
    # Secondary (use sparingly)
    "<chuckle>", "<giggle>", "<moan>", "<sad>",
]

EMOTION_TO_TAG = {
    "happy": "<chuckle>",
    "amused": "<laugh>",
    "funny": "<laugh>",
    "wry": "<chuckle>",
    "sad": "<sad>",
    "tired": "<yawn>",
    "weary": "<sigh>",
    "resigned": "<sigh>",
    "frustrated": "<groan>",
    "annoyed": "<groan>",
    "surprised": "<gasp>",
    "scared": "<scream>",
    "shocked": "<gasp>",
    "sick": "<cough>",
    "crying": "<sad>",
    "horrified": "<scream>",
    "terrified": "<scream>",
    "pain": "<moan>",
    "suffering": "<moan>",
    "giddy": "<giggle>",
    "silly": "<giggle>",
    "melancholy": "<sad>",
    "grief": "<sad>",
    "laugh": "<laugh>",
    "chuckle": "<chuckle>",
    "giggle": "<giggle>",
    "sigh": "<sigh>",
    "cough": "<cough>",
    "groan": "<groan>",
    "moan": "<moan>",
    "yawn": "<yawn>",
    "gasp": "<gasp>",
    "scream": "<scream>",
    "sad": "<sad>",
    "neutral": "",
    "calm": "",
    "serious": "",
    "thoughtful": "",
    "determined": "",
}

GEMINI_FLASH_MODEL = "gemini-3.1-flash-lite-preview"

# Regex to match closing tags like </laugh>, </sigh>, etc.
_CLOSING_TAG_RE = re.compile(r"</(?:laugh|chuckle|giggle|sigh|cough|groan|moan|yawn|gasp|scream|sad)>")

SUBTLE_SYSTEM_PROMPT = """\
You are an expert audiobook director analyzing text for emotional delivery cues.
Your job is to insert Orpheus TTS emotion tags into narrative text so a text-to-speech
engine can deliver expressive, natural-sounding narration.

PRIMARY tags (insert BEFORE the sentence or clause they apply to):
- <laugh> — full laughter, humor, amusement, comedy
- <sigh> — weariness, resignation, exhaustion, relief, boredom
- <cough> — discomfort, illness, clearing throat, interruption
- <groan> — frustration, annoyance, exasperation
- <yawn> — tiredness, boredom, sleepiness
- <gasp> — shock, sudden realization, surprise
- <scream> — terror, horror, extreme fear, screaming

SECONDARY tags (use sparingly — these work but are less reliable):
- <chuckle> — wry amusement, mild humor, ironic observations
- <giggle> — giddy amusement, nervous laughter, silliness
- <moan> — pain, suffering, physical distress
- <sad> — grief, sadness, crying, loss, melancholy

CRITICAL — Orpheus tags are OPENING ONLY. NEVER output closing tags like </laugh> or </sigh>.
A new opening tag automatically ends the previous emotion. Wrong: <gasp>No!</gasp>  Correct: <gasp>No!

Rules for SUBTLE mode:
1. Only tag sentences with CLEAR, unmistakable emotional content.
2. Skip neutral narration, descriptions, and exposition — leave them untagged.
3. Tag at most 20-30% of sentences. Most text should pass through unchanged.
4. Prefer PRIMARY tags. Only use SECONDARY tags when the emotion is a strong match.
5. For dialogue, infer speaker emotion from dialogue tags and context (e.g. "she sobbed" → <sad>).
6. Place the tag at the START of the sentence or clause it applies to.
7. Do NOT alter the original text in any way — only insert tags.
8. Do NOT add tags inside words or break up sentences.
9. Preserve all original punctuation, spacing, and line breaks exactly.
10. NEVER use closing tags (e.g. </laugh>, </sigh>). Only opening tags are valid.

Return ONLY the tagged text. No explanations, no markdown, no commentary."""

DRAMATIC_SYSTEM_PROMPT = """\
You are an expressive audiobook director analyzing text for emotional delivery cues.
Your job is to insert Orpheus TTS emotion tags into narrative text so a text-to-speech
engine delivers a richly expressive, dramatic performance.

PRIMARY tags (insert BEFORE the sentence or clause they apply to):
- <laugh> — full laughter, humor, amusement, irony, comedy
- <sigh> — weariness, resignation, exhaustion, boredom, relief
- <cough> — discomfort, illness, clearing throat, interruption
- <groan> — frustration, annoyance, exasperation
- <yawn> — tiredness, boredom, sleepiness, lethargy
- <gasp> — shock, sudden realization, suspense, surprise
- <scream> — terror, horror, extreme fear, panic, screaming

SECONDARY tags (these work but are less reliable — use when the emotion is clear):
- <chuckle> — wry amusement, mild humor, sardonic observations
- <giggle> — giddy amusement, nervous laughter, silliness
- <moan> — pain, suffering, physical distress, agony
- <sad> — grief, loss, sadness, crying, emotional overwhelm, longing

CRITICAL — Orpheus tags are OPENING ONLY. NEVER output closing tags like </laugh> or </sigh>.
A new opening tag automatically ends the previous emotion. Wrong: <gasp>No!</gasp>  Correct: <gasp>No!

Rules for DRAMATIC mode:
1. Tag 50-70% of sentences — be generous with emotional interpretation.
2. Infer emotion from context even when not explicitly stated.
   - Action scenes → <gasp> or <groan>
   - Horror/fear scenes → <scream> or <gasp>
   - Quiet scenes → <sigh> or <yawn>
   - Sad scenes → <sad> or <sigh>
   - Dialogue → detect speaker emotion from context clues, dialogue tags, and subtext.
   - Internal monologue → match the character's emotional state.
3. Layer emotions: if a passage shifts emotion mid-paragraph, tag each shift.
4. For dialogue, consider:
   - The dialogue tag ("he sighed", "she gasped")
   - What just happened in the scene
   - The character's arc and relationships
   - Subtext and irony
5. Prefer PRIMARY tags. Use SECONDARY tags when the emotion is a strong match.
6. Place the tag at the START of the sentence or clause it applies to.
7. Do NOT alter the original text in any way — only insert tags.
8. Do NOT add tags inside words or break up sentences.
9. Preserve all original punctuation, spacing, and line breaks exactly.
10. When in doubt, tag it. Better too expressive than flat.
11. NEVER use closing tags (e.g. </laugh>, </sigh>). Only opening tags are valid.

Return ONLY the tagged text. No explanations, no markdown, no commentary."""


class EmotionTagger:
    """Analyzes text and inserts Orpheus emotion tags using Gemini Flash."""

    def __init__(self, api_key: str = None, level: str = "subtle"):
        """
        Args:
            api_key: Gemini API key. Falls back to GEMINI_API_KEY env var.
            level: Emotion level — "none", "subtle", or "dramatic".
        """
        self.level = level.lower()
        if self.level not in ("none", "subtle", "dramatic"):
            raise ValueError(f"Invalid emotion level: {level}. Must be none/subtle/dramatic.")

        if self.level == "none":
            self._client = None
            return

        resolved_key = api_key or os.environ.get("GEMINI_API_KEY")
        if not resolved_key:
            raise ValueError("GEMINI_API_KEY required for emotion tagging (level != none)")

        self._client = genai.Client(api_key=resolved_key)
        self._system_prompt = SUBTLE_SYSTEM_PROMPT if self.level == "subtle" else DRAMATIC_SYSTEM_PROMPT

    def tag(self, text: str) -> str:
        """Insert emotion tags into text.

        For short text (under ~6000 words), processes in one call.
        For longer text, splits into sections at paragraph boundaries
        and processes each section separately.

        Args:
            text: Raw text to analyze and tag.

        Returns:
            Text with Orpheus emotion tags inserted inline.
        """
        if self.level == "none" or not text.strip():
            return text

        # For short text, process in one shot
        words = text.split()
        if len(words) <= 6000:
            return self._call_gemini(text)

        # For longer text, split into ~5000-word sections at paragraph breaks
        return self._tag_in_sections(text)

    def _tag_in_sections(self, text: str) -> str:
        """Split long text into sections and tag each one."""
        paragraphs = text.split("\n\n")
        sections = []
        current_section = []
        current_word_count = 0

        for para in paragraphs:
            para_words = len(para.split())
            if current_word_count + para_words > 5000 and current_section:
                sections.append("\n\n".join(current_section))
                current_section = []
                current_word_count = 0
            current_section.append(para)
            current_word_count += para_words

        if current_section:
            sections.append("\n\n".join(current_section))

        logger.info(f"Split text into {len(sections)} sections for tagging")

        tagged_sections = []
        for i, section in enumerate(sections):
            logger.info(f"Tagging section {i + 1}/{len(sections)} ({len(section.split())} words)")
            tagged_sections.append(self._call_gemini(section))

        return "\n\n".join(tagged_sections)

    def _call_gemini(self, text: str) -> str:
        """Call Gemini Flash to analyze and tag the text."""
        user_prompt = f"Insert emotion tags into this text for audiobook narration:\n\n{text}"

        try:
            response = self._client.models.generate_content(
                model=GEMINI_FLASH_MODEL,
                contents=user_prompt,
                config={
                    "system_instruction": self._system_prompt,
                    "temperature": 0.3,
                    "max_output_tokens": 8192,
                },
            )

            if not response or not response.text:
                logger.warning("Empty response from Gemini Flash, returning original text")
                return text

            tagged = response.text.strip()

            # Strip any closing tags the model may have generated
            tagged = _CLOSING_TAG_RE.sub("", tagged)

            # Validate: the tagged text should contain the original words
            tagged = self._validate_output(text, tagged)
            return tagged

        except Exception as e:
            logger.error(f"Gemini Flash emotion tagging failed: {e}")
            return text  # Graceful fallback — return untagged

    def _validate_output(self, original: str, tagged: str) -> str:
        """Validate that tagged output preserves original text content."""
        # Strip all emotion tags (opening and any stray closing) from the output
        stripped = tagged
        for tag in ORPHEUS_EMOTION_TAGS:
            stripped = stripped.replace(tag, "")
        stripped = _CLOSING_TAG_RE.sub("", stripped)

        # Normalize whitespace for comparison
        orig_words = original.split()
        stripped_words = stripped.split()

        # If word count diverges by more than 5%, the model likely hallucinated
        if orig_words and abs(len(stripped_words) - len(orig_words)) / len(orig_words) > 0.05:
            logger.warning(
                f"Emotion tagger output diverged from original "
                f"({len(orig_words)} vs {len(stripped_words)} words). "
                f"Returning original text."
            )
            return original

        return tagged

    def tag_count(self, text: str) -> dict:
        """Count emotion tags in tagged text. Useful for metrics."""
        counts = {}
        for tag in ORPHEUS_EMOTION_TAGS:
            c = text.count(tag)
            if c > 0:
                counts[tag] = c
        return counts
