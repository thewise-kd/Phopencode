package io.github.thewisekd.phopencode.core.codeview

import android.content.Context
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import java.io.InputStream
import java.io.StringReader
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the TextMate grammars and themes from the assets into registries of its own, so
 * nothing depends on sora-editor's process-wide singletons.
 *
 * - **Lazily and once:** a grammar or theme is read the first time it is asked for and
 *   kept for the life of the loader; a later request, or one for another language sharing
 *   the grammar, reads nothing. A grammar that fails to load is remembered as failed, and
 *   its language shows as plain text.
 * - **Threads:** [loadGrammar] parses large JSON files and runs off the main thread; loads
 *   are serialized under a lock of their own. [loadTheme], [applyTheme], [colorScheme] and
 *   [createLanguage] run on the main thread and never wait for a grammar that is loading:
 *   each grammar has its own `GrammarRegistry`, which the main thread touches only after
 *   its load has finished, and the themes have their own lock.
 * - **One theme at a time:** the current theme belongs to the loader, not to an editor.
 *   [applyTheme] recolors every editor that uses this loader, so with the [shared] loader
 *   all CodeViews in the process are dark or light together. That is a known limit, kept
 *   on purpose: the app theme is global, so two editors never need different themes.
 *
 * The app uses one [shared] loader.
 */
internal class TextMateLoader(private val open: (String) -> InputStream) {
    val themes = ThemeRegistry()

    /** Serializes grammar loads; only background threads take it. */
    private val loadLock = Any()

    /** Guards [ready] and [currentTheme]; held only for quick work, on any thread. */
    private val themeLock = Any()

    /** The outcome of each grammar load: its registry, or null when it failed. Read without a lock. */
    private val results = ConcurrentHashMap<TextMateGrammar, Optional<GrammarRegistry>>()

    private val ready = ArrayList<GrammarRegistry>()
    private val themeNames = HashMap<GrammarRegistry, String>()
    private var currentTheme: ThemeModel? = null
    private val themeResults = HashMap<Boolean, Boolean>()

    /** Loads the grammar of [language] if it has one. Returns whether it is available. Not on the main thread. */
    fun loadGrammar(language: CodeLanguage): Boolean {
        val grammar = TextMateGrammars.of(language) ?: return false
        results[grammar]?.let { return it.isPresent }
        synchronized(loadLock) {
            results[grammar]?.let { return it.isPresent }
            val registry = runCatching { load(grammar) }.getOrNull()
            if (registry != null) {
                synchronized(themeLock) {
                    currentTheme?.let { setTheme(registry, it) }
                    ready += registry
                }
            }
            results[grammar] = Optional.ofNullable(registry)
            return registry != null
        }
    }

    // languageConfigurationToGrammar is deprecated in favor of a definition with a configuration
    // path, which sora resolves through its process-wide FileProviderRegistry; reading the file
    // here keeps every read behind [open].
    @Suppress("DEPRECATION")
    private fun load(grammar: TextMateGrammar): GrammarRegistry {
        val registry = GrammarRegistry(null)
        grammar.files.forEach { file ->
            val source = IGrammarSource.fromString(IGrammarSource.ContentType.JSON, read(grammar.path(file)))
            val loaded = registry.loadGrammar(DefaultGrammarDefinition.withGrammarSource(source, file.file, file.scopeName))
            if (file == grammar.main) {
                val configuration = LanguageConfiguration.load(StringReader(read(grammar.languageConfiguration)))
                registry.languageConfigurationToGrammar(configuration, loaded)
            }
        }
        return registry
    }

    /** Parses the dark or light theme without making it current. Returns whether it is available. */
    fun loadTheme(dark: Boolean): Boolean = synchronized(themeLock) {
        themeResults.getOrPut(dark) {
            runCatching {
                val source = IThemeSource.fromString(IThemeSource.ContentType.JSON, read(TextMateThemes.path(dark)))
                val model = ThemeModel(source, TextMateThemes.name(dark)).apply { isDark = dark }
                themes.loadTheme(model, false)
            }.isSuccess
        }
    }

    /**
     * Makes the loaded dark or light theme current. Every editor colored by this loader
     * follows, not only the caller's: the theme is shared (see the class comment).
     */
    fun applyTheme(dark: Boolean) {
        val name = TextMateThemes.name(dark)
        val model = themes.findThemeByThemeName(name) ?: return
        // The grammars first, so the editors' re-highlighting below already uses the new colors.
        synchronized(themeLock) {
            if (currentTheme === model) return
            currentTheme = model
            ready.forEach { setTheme(it, model) }
        }
        themes.setTheme(name)
    }

    private fun setTheme(registry: GrammarRegistry, model: ThemeModel) {
        runCatching { registry.setTheme(model) }
        themeNames[registry] = model.name
    }

    /** A color scheme that follows [applyTheme]. Call after a theme is applied. */
    fun colorScheme(): EditorColorScheme = TextMateColorScheme.create(themes, themes.currentThemeModel)

    /** A new highlighter for [language], or null when its grammar is not loaded (yet). */
    fun createLanguage(language: CodeLanguage): Language? {
        val grammar = TextMateGrammars.of(language) ?: return null
        val registry = grammarRegistry(language) ?: return null
        return TextMateLanguage.create(grammar.main.scopeName, registry, themes, false)
    }

    /** The registry of [language]'s grammar once it has loaded, for tests. */
    fun grammarRegistry(language: CodeLanguage): GrammarRegistry? =
        TextMateGrammars.of(language)?.let { results[it] }?.orElse(null)

    /** The theme last given to [language]'s grammar, for tests. */
    fun grammarThemeName(language: CodeLanguage): String? {
        val registry = grammarRegistry(language) ?: return null
        return synchronized(themeLock) { themeNames[registry] }
    }

    private fun read(path: String): String = open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        @Volatile private var instance: TextMateLoader? = null

        /** The process-wide loader over the application's assets. */
        fun shared(context: Context): TextMateLoader = instance ?: synchronized(this) {
            instance ?: context.applicationContext.assets.let { assets ->
                TextMateLoader { path -> assets.open(path) }
            }.also { instance = it }
        }
    }
}
