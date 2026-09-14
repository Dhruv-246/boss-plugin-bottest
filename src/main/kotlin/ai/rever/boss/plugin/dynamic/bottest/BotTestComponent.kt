package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext

/**
 * Placeholder panel. Replaced by the run/compare UI once the evaluation engine
 * lands; for now it exists so the plugin registers a real panel and the host's
 * load path can be verified.
 */
class BotTestComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        // Always wrap plugin UI in BossTheme so it follows the host theme.
        BossTheme {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Bot Test", style = MaterialTheme.typography.subtitle1)
                Text(
                    "Skeleton installed. The evaluation engine is not implemented yet.",
                    style = MaterialTheme.typography.body2,
                )
                Text(
                    "MCP: call bottest_info from an agent to verify the tool bridge.",
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}
