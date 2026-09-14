package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle

/**
 * Panel descriptor for Bot Test. Placement mirrors the manifest's
 * `panel.position` / `panel.priority`.
 */
object BotTestInfo : PanelInfo {
    override val id = PanelId("bot-test", 60)
    override val displayName = "Bot Test"
    override val icon = FeatherIcons.CheckCircle
    override val defaultSlotPosition = left.bottom
}
