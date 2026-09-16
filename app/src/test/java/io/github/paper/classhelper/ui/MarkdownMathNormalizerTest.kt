package io.github.paper.classhelper.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownMathNormalizerTest {
    @Test
    fun convertsCommonInlineMathDialects() {
        assertEquals(
            "欧拉公式：\$\$e^{i\\pi}+1=0\$\$",
            MarkdownMathNormalizer.normalize("欧拉公式：\$e^{i\\pi}+1=0\$"),
        )
        assertEquals(
            "向量：\$\$a+b=c\$\$",
            MarkdownMathNormalizer.normalize("向量：\\(a+b=c\\)"),
        )
    }

    @Test
    fun convertsBracketDisplayMathToBlockMath() {
        assertEquals(
            "推导：\n\$\$\nE=mc^2\n\$\$\n结束",
            MarkdownMathNormalizer.normalize("推导：\n\\[\nE=mc^2\n\\]\n结束"),
        )
    }

    @Test
    fun preservesExistingDoubleDollarMath() {
        val markdown = "行内 \$\$x^2+y^2=z^2\$\$\n\n\$\$\n\\int_0^1 x\\,dx\n\$\$"
        assertEquals(markdown, MarkdownMathNormalizer.normalize(markdown))
    }

    @Test
    fun doesNotTouchInlineOrFencedCode() {
        val markdown = "代码 `\$x^2\$` 不应解析\n```kotlin\nval price = \"\$12\"\nval latex = \"\$x\$\"\n```"
        assertEquals(markdown, MarkdownMathNormalizer.normalize(markdown))
    }

    @Test
    fun keepsCurrencyLookingTextPlain() {
        assertEquals(
            "价格区间 \$12.50\$ 到 \$18\$",
            MarkdownMathNormalizer.normalize("价格区间 \$12.50\$ 到 \$18\$"),
        )
    }

    @Test
    fun leavesMarkdownFormattingIntact() {
        val markdown = "## 结论\n\n- **重点**：\$F=ma\$\n- `code`\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val expected = "## 结论\n\n- **重点**：\$\$F=ma\$\$\n- `code`\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        assertEquals(expected, MarkdownMathNormalizer.normalize(markdown))
    }
}
