#!/usr/bin/env python3
"""
Create a simple test PDF with chapters for testing the TTS pipeline
"""

from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, PageBreak
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import inch

def create_test_pdf(filename="test_book.pdf"):
    # Create the PDF document
    doc = SimpleDocTemplate(filename, pagesize=letter,
                            rightMargin=72, leftMargin=72,
                            topMargin=72, bottomMargin=18)

    # Get styles
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        'CustomTitle',
        parent=styles['Heading1'],
        fontSize=24,
        spaceAfter=30,
    )
    chapter_style = ParagraphStyle(
        'Chapter',
        parent=styles['Heading1'],
        fontSize=18,
        spaceAfter=12,
    )
    body_style = styles['Normal']

    # Create content
    story = []

    # Title page
    story.append(Paragraph("The Test Book", title_style))
    story.append(Paragraph("A Simple Book for Testing TTS Pipeline", styles['Heading2']))
    story.append(PageBreak())

    # Chapter 1
    story.append(Paragraph("Chapter 1: Introduction", chapter_style))
    story.append(Paragraph("""
    This is the first chapter of our test book. It contains some sample text
    that will be processed by the Gemini API to test our text-to-speech pipeline.
    The purpose of this chapter is to verify that the system can correctly identify
    chapter boundaries and extract the text content.
    """, body_style))
    story.append(Paragraph("""
    In this introduction, we explore the basic concepts and set up the foundation
    for the rest of the book. The text should be long enough to test the chunking
    mechanism if needed, but short enough for quick testing cycles.
    """, body_style))
    story.append(PageBreak())

    # Chapter 2
    story.append(Paragraph("Chapter 2: The Journey Begins", chapter_style))
    story.append(Paragraph("""
    Our second chapter marks the beginning of the actual journey. Here we have
    more content to process and convert to speech. The Gemini model should be
    able to identify this as a separate chapter with its own title.
    """, body_style))
    story.append(Paragraph("""
    As we continue through this chapter, we add more paragraphs to ensure that
    the text extraction works properly across multiple paragraphs and potentially
    across multiple pages. This will help us verify that the page range detection
    in the orchestrator lambda is working correctly.
    """, body_style))
    story.append(Paragraph("""
    The journey takes us through various landscapes of text and narrative,
    each designed to test different aspects of our processing pipeline.
    """, body_style))
    story.append(PageBreak())

    # Chapter 3
    story.append(Paragraph("Chapter 3: Conclusion", chapter_style))
    story.append(Paragraph("""
    In this final chapter, we conclude our test book. This shorter chapter
    will help us verify that the system handles chapters of varying lengths
    appropriately.
    """, body_style))
    story.append(Paragraph("""
    Thank you for reading this test book. If you can hear this through the
    text-to-speech system, then our pipeline is working correctly!
    """, body_style))

    # Build the PDF
    doc.build(story)
    print(f"Created test PDF: {filename}")

if __name__ == "__main__":
    try:
        create_test_pdf()
    except ImportError as e:
        print("reportlab not installed. Installing it now...")
        import subprocess
        subprocess.run(["pip", "install", "reportlab"], check=True)
        print("Installed reportlab. Running again...")
        from reportlab.lib.pagesizes import letter
        from reportlab.platypus import SimpleDocTemplate, Paragraph, PageBreak
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib.units import inch
        create_test_pdf()