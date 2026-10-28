package com.slopus.happy.icons

import com.slopus.happy.layout.PathElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyIconsTests/FileIconsTests.swift.

class FileIconsTest {
    /** What @peoplesgrocers/seti-ui-file-icons 1.11.3's `getIcon` answers, run on the package's own definitions.json. */
    @Test fun lookupMatchesThePackage() {
        val c = SetiColor.entries.associateBy { it.rawValue }
        val expected = listOf(
            Triple("package.json", "json", "yellow"),
            Triple("index.ts", "typescript", "blue"),
            Triple("App.tsx", "react", "blue"),
            Triple("README.md", "info", "blue"),
            Triple("Makefile", "makefile", "orange"),
            Triple("Dockerfile", "docker", "blue"),
            Triple("foo.test.ts", "typescript", "orange"),
            Triple("a.d.ts", "typescript", "blue"),
            Triple("archive.tar.gz", "default", "white"),
            Triple(".gitignore", "git", "ignore"),
            Triple(".env", "config", "grey-light"),
            Triple(".env.local", "default", "white"),
            Triple("Cargo.toml", "config", "grey-light"),
            Triple("main.rs", "rust", "grey-light"),
            Triple("notes", "default", "white"),
            Triple("", "default", "white"),
            Triple(".", "default", "white"),
            Triple("x.", "default", "white"),
            Triple("weird.unknownext", "default", "white"),
            Triple("TODO.md", "markdown", "blue"),
            Triple("my.TODO.txt", "default", "white"),
            Triple("CMakeLists.txt", "default", "white"),
            Triple("Procfile", "heroku", "purple"),
            Triple("docker-compose.yml", "yml", "purple"),
            Triple("logo.png", "image", "purple"),
            Triple("icon.svg", "svg", "purple"),
            Triple("style.module.css", "css", "blue"),
            Triple("view.html", "html", "orange"),
            Triple("main.go", "go2", "blue"),
            Triple("yarn.lock", "yarn", "blue"),
            Triple("pnpm-lock.yaml", "yml", "purple"),
            Triple("tsconfig.json", "tsconfig", "blue"),
            Triple("tsconfig.base.json", "json", "yellow"),
            Triple("vite.config.ts", "vite", "yellow"),
            Triple("webpack.config.js", "webpack", "blue"),
            Triple("Gemfile", "ruby", "red"),
            Triple("build.gradle", "gradle", "blue"),
            Triple("x.kt", "kotlin", "orange"),
            Triple("a.R", "R", "blue"),
            Triple("mix.exs", "elixir_script", "purple"),
            Triple("z.ex", "elixir", "purple"),
            Triple("q.pl", "perl", "blue"),
            Triple("karma.conf.js", "karma", "green"),
            Triple("spec.rb", "ruby", "red"),
            Triple("é.swift", "swift", "orange"),
            Triple("Ｋ.md", "markdown", "blue"),
            Triple("gulpfile.js", "javascript", "yellow"),
            Triple("LICENSE", "license", "yellow"),
            Triple("license.txt", "default", "white"),
            Triple("jest.config.ts", "typescript", "blue"),
        )
        for ((name, icon, color) in expected) {
            val found = FileIcons.lookup(name)
            assertEquals(name, icon, found.icon)
            assertEquals(name, c[color], found.color)
        }
    }

    @Test fun everyIconParses() {
        assertEquals("1.11.3", FileIcons.SOURCE_VERSION)
        val lines = FileIcons.iconLines
        assertEquals(151, FileIcons.allIcons.size)
        assertEquals(151, lines.size)
        for (line in lines) {
            val name = line.split(' ')[0]
            val shapes = line.split(' ', limit = 3)[2].split(';').size
            val outline = FileIcons.outlines[name]
            assertNotNull("$name doesn't parse", outline)
            assertEquals(name, shapes, outline!!.shapes.size)
            assertTrue(name, outline.viewBox.width > 0)
            for (shape in outline.shapes) {
                assertFalse(name, shape.path.elements.isEmpty())
                assertTrue(name, shape.opacity > 0 && shape.opacity <= 1)
            }
        }
        assertNotNull(FileIcons.icon("anything"))
    }

    @Test fun shapesKeepTheirRuleAndOpacity() {
        // font.svg: a 60% shape under a solid one.
        assertEquals(listOf(0.6, 1.0), FileIcons.outlines["font"]!!.shapes.map { it.opacity })
        // gradle draws through <use fill-rule="evenodd">.
        assertEquals(listOf(true), FileIcons.outlines["gradle"]!!.shapes.map { it.evenOdd })
        // prolog's two circles became closed curves.
        val prolog = FileIcons.outlines["prolog"]!!
        assertEquals(3, prolog.shapes.size)
        assertEquals(PathElement.Close, prolog.shapes[1].path.elements.last())
    }
}
