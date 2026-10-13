/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import org.k3lp.lib.meta.source.SourceFileRef
import org.k3lp.lib.meta.source.TextSourceFile
import org.k3lp.lib.text.WillRequireMigrationToRichErrors
import org.k3lp.model.K3CompileResult
import org.k3lp.model.K3ImpliedImports
import org.k3lp.model.K3ImportResolver
import org.k3lp.model.compileToModel
import java.io.File

/**
 * Compile-smoke tests for the keyboard3 layout assets. Replicates the runtime
 * loading path in ime/keyboard3/extension/Loader.kt on the JVM: reads the layout
 * XML from the k3.foundation extension assets, resolves flex:// imports from the
 * extension's import/ directory (base="cldr" imports are bundled in k3lp itself),
 * and compiles it to a K3 model.
 */
@OptIn(WillRequireMigrationToRichErrors::class)
class Keyboard3LayoutCompileTest : FunSpec({
    val candidates = listOf(
        "src/main/assets/extensions/org.florisboard.k3.foundation",
        "app/src/main/assets/extensions/org.florisboard.k3.foundation",
    )
    val assetsRoot = candidates.map(::File).firstOrNull(File::isDirectory)
        ?: error("k3.foundation assets not found (working dir: ${File("").absolutePath})")

    suspend fun compileLayout(fileName: String): K3CompileResult {
        val xml = File(assetsRoot, "keyboard/$fileName").readText()
        val file = TextSourceFile(object : SourceFileRef {
            override fun toString() = "keyboard/$fileName"
        }, xml)
        val resolver = K3ImportResolver { path, _ ->
            // Mirrors Loader.kt: flex:// imports are read from the extension's
            // import/ directory; the path may arrive full or already stripped.
            val relPath = path
                .removePrefix("flex://org.florisboard.k3.foundation/")
                .removePrefix("/")
            val importFile = File(assetsRoot, "import/$relPath")
            TextSourceFile(object : SourceFileRef {
                override fun toString() = "import/$relPath"
            }, importFile.readText())
        }
        val impliedImports = K3ImpliedImports(
            displays = listOf("flex://org.florisboard.k3.foundation/displays-implied.xml"),
            keys = listOf("flex://org.florisboard.k3.foundation/keys-implied.xml"),
        )
        return file.compileToModel(resolver, impliedImports)
    }

    suspend fun expectCompileSuccess(fileName: String) {
        val result = compileLayout(fileName)
        if (result !is K3CompileResult.Success) {
            val details = result.reports.joinToString(separator = "\n") { report ->
                report.cause?.stackTraceToString() ?: report.toString()
            }
            error("$fileName failed to compile:\n$details")
        }
        result.shouldBeInstanceOf<K3CompileResult.Success>()
    }

    test("qwertz.xml compiles to a keyboard model (regression guard)") {
        expectCompileSuccess("qwertz.xml")
    }

    test("qwerty.xml compiles to a keyboard model") {
        expectCompileSuccess("qwerty.xml")
    }
})
