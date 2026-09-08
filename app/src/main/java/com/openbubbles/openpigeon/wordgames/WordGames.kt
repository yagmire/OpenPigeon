package com.openbubbles.openpigeon.wordgames

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ImageProvider
import com.openbubbles.openpigeon.Game
import com.openbubbles.openpigeon.GameImageChoice
import com.openbubbles.openpigeon.GameNotFound
import com.openbubbles.openpigeon.R
import com.openbubbles.openpigeon.RenderGameChoiceTiles
import com.openbubbles.openpigeon.anagrams.AnagramsGame
import com.openbubbles.openpigeon.wordbites.WordbitesGame
import com.openbubbles.openpigeon.wordhunt.WordHuntGame
import com.openbubbles.openpigeon.settings.GameStats

class WordGames : Game {
    private val TAG = "WordGames"

    override fun getName(): String {
        return "wordgames"
    }

    override fun isConfigurable(): Boolean {
        return true
    }

    @Composable
    override fun Configuration(
        context: Context?,
    ) {
        val anagrams = AnagramsGame()
        val wordHunt = WordHuntGame()
        val wordBites = WordbitesGame()

        if (context != null) GameStats.init(context)

        RenderGameChoiceTiles(
            title = "Choose Game",
            choices = listOf(
                GameImageChoice(
                    game = anagrams,
                    label = "Anagrams",
                    image = ImageProvider(R.drawable.anagrams_6l),
                    wins = if (context != null) GameStats.getWins(anagrams.getName()) else 0,
                ),
                GameImageChoice(
                    game = wordHunt,
                    label = "Word Hunt",
                    image = ImageProvider(R.drawable.wordhunt),
                    wins = if (context != null) GameStats.getWins(wordHunt.getName()) else 0,
                ),
                GameImageChoice(
                    game = wordBites,
                    label = "Word Bites",
                    image = ImageProvider(R.drawable.wordbites),
                    wins = if (context != null) GameStats.getWins(wordBites.getName()) else 0,
                ),
            ),
            imageHeight = 74.dp,
        )
    }

    override fun setConfigOption(name: String, value: String) {
    }

    override fun gameClass(): Class<*> {
        return GameNotFound::class.java
    }

    override fun gamePoster(config: Map<String, String>?): Int {
        return R.drawable.wordgames
    }

    override fun displayName(): String {
        return "Word Games"
    }

    override fun getVersion(): String {
        return "47"
    }

    override fun getNewGameData(context: Context): MutableMap<String, String>? {
        return null
    }

    override fun getDefaultReplay(): String {
        return "{}"
    }
}
