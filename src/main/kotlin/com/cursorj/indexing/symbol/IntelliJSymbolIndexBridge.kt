package com.cursorj.indexing.symbol

import com.cursorj.acp.messages.SymbolInfo
import com.cursorj.acp.messages.SymbolLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class IntelliJSymbolIndexBridge(
    private val project: Project,
) {
    open suspend fun findSymbols(
        query: String,
        path: String? = null,
        maxResults: Int = 25,
    ): List<SymbolInfo> = withContext(Dispatchers.Default) {
        if (query.isBlank() || DumbService.isDumb(project)) return@withContext emptyList()
        val results = mutableListOf<SymbolInfo>()
        ApplicationManager.getApplication().runReadAction {
            iterateCandidateFiles(path) { psiFile ->
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                        if (results.size >= maxResults) return
                        val named = element as? PsiNamedElement
                        val name = named?.name
                        if (!name.isNullOrBlank() && name.contains(query, ignoreCase = true)) {
                            val location = createLocation(element)
                            results.add(
                                SymbolInfo(
                                    id = "${psiFile.virtualFile.path}:${element.textRange.startOffset}",
                                    kind = element.javaClass.simpleName,
                                    name = name,
                                    displayName = name,
                                    fqName = null,
                                    location = location,
                                    score = scoreName(query, name),
                                ),
                            )
                        }
                        if (results.size < maxResults) {
                            super.visitElement(element)
                        }
                    }
                })
                results.size < maxResults
            }
        }
        results.sortedByDescending { it.score ?: 0.0 }.take(maxResults)
    }

    open suspend fun listFileSymbols(path: String, maxResults: Int = 200): List<SymbolInfo> = withContext(Dispatchers.Default) {
        if (path.isBlank() || DumbService.isDumb(project)) return@withContext emptyList()
        ApplicationManager.getApplication().runReadAction<List<SymbolInfo>> {
            val psiFile = resolvePsiFile(path) ?: return@runReadAction emptyList()
            val results = mutableListOf<SymbolInfo>()
            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: com.intellij.psi.PsiElement) {
                    if (results.size >= maxResults) return
                    val named = element as? PsiNamedElement
                    val name = named?.name
                    if (!name.isNullOrBlank()) {
                        results.add(
                            SymbolInfo(
                                id = "${psiFile.virtualFile.path}:${element.textRange.startOffset}",
                                kind = element.javaClass.simpleName,
                                name = name,
                                displayName = name,
                                location = createLocation(element),
                                score = 1.0,
                            ),
                        )
                    }
                    if (results.size < maxResults) {
                        super.visitElement(element)
                    }
                }
            })
            results
        }
    }

    open suspend fun findReferences(
        path: String,
        line: Int,
        column: Int,
        maxResults: Int = 100,
    ): List<SymbolLocation> = withContext(Dispatchers.Default) {
        if (DumbService.isDumb(project)) return@withContext emptyList()
        ApplicationManager.getApplication().runReadAction<List<SymbolLocation>> {
            val psiFile = resolvePsiFile(path) ?: return@runReadAction emptyList()
            val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile) ?: return@runReadAction emptyList()
            val offset = toOffset(document, line, column) ?: return@runReadAction emptyList()
            val element = psiFile.findElementAt(offset) ?: return@runReadAction emptyList()
            val target = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false) ?: return@runReadAction emptyList()
            val scope = GlobalSearchScope.projectScope(project)
            val query = ReferencesSearch.search(target, scope)
            val refs = mutableListOf<SymbolLocation>()
            for (reference in query.findAll()) {
                val refElement = reference.element
                createLocation(refElement)?.let { refs.add(it) }
                if (refs.size >= maxResults) break
            }
            refs
        }
    }

    private fun iterateCandidateFiles(path: String?, consumer: (com.intellij.psi.PsiFile) -> Boolean) {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val resolvedPath = path?.takeIf { it.isNotBlank() }?.replace('\\', '/')
        fileIndex.iterateContent { virtualFile ->
            if (virtualFile.isDirectory) return@iterateContent true
            if (resolvedPath != null && !virtualFile.path.replace('\\', '/').startsWith(resolvedPath)) {
                return@iterateContent true
            }
            val psiFile = psiManager.findFile(virtualFile) ?: return@iterateContent true
            consumer(psiFile)
        }
    }

    private fun resolvePsiFile(path: String): com.intellij.psi.PsiFile? {
        val normalized = path.replace('\\', '/')
        val absolutePath = if (java.io.File(normalized).isAbsolute) normalized else {
            val base = project.basePath ?: return null
            java.io.File(base, normalized).absolutePath.replace('\\', '/')
        }
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
            ?: return null
        return PsiManager.getInstance(project).findFile(vFile)
    }

    private fun createLocation(element: com.intellij.psi.PsiElement): SymbolLocation? {
        val file = PsiUtilCore.getVirtualFile(element) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val range = element.textRange ?: return null
        val startLine = document.getLineNumber(range.startOffset) + 1
        val endLine = document.getLineNumber((range.endOffset - 1).coerceAtLeast(range.startOffset)) + 1
        val startColumn = range.startOffset - document.getLineStartOffset(startLine - 1) + 1
        val endColumn = range.endOffset - document.getLineStartOffset(endLine - 1) + 1
        return SymbolLocation(
            path = file.path.replace('\\', '/'),
            startLine = startLine,
            startColumn = startColumn,
            endLine = endLine,
            endColumn = endColumn,
        )
    }

    private fun toOffset(document: Document, line: Int, column: Int): Int? {
        val zeroLine = (line - 1).coerceAtLeast(0)
        if (zeroLine >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(zeroLine)
        val lineEnd = document.getLineEndOffset(zeroLine)
        val zeroColumn = (column - 1).coerceAtLeast(0)
        return (lineStart + zeroColumn).coerceAtMost(lineEnd)
    }

    private fun scoreName(query: String, name: String): Double {
        if (query.equals(name, ignoreCase = true)) return 1.0
        if (name.startsWith(query, ignoreCase = true)) return 0.85
        return 0.6
    }
}
