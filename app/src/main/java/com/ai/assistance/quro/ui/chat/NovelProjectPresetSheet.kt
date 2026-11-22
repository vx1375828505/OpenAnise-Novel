package com.ai.assistance.quro.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.data.repository.SkillRepository

/**
 * [T-novel-project-entry] 「新建小说项目」预设选择面板。
 *
 * 开新书以前只能靠用户自己敲 `/novel-project`：菜单里没有入口，用户得先知道这个技能存在。
 * 这个 Sheet 把入口摆到聊天菜单里——列出已安装的 `novel-*` 预设，点一个就把
 * 「用该预设开新书」的指令写进输入框（真正建目录仍由技能按项目模板跑，
 * 这里不建项目、不碰文件系统、不新增路由）。
 *
 * 外壳用 [StandardChatSheet]（聊天菜单里所有面板的同一套 ModalBottomSheet 封装：
 * 一致的拖动条、标题与关闭按钮），与 SessionSkillsSheet / TokenUsageSheet 保持同一层级手感。
 *
 * @param presets 可选的预设技能；调用方从 SkillRepository 取快照传进来，缺省为空时显示安装提示。
 */
@Composable
fun NovelProjectPresetSheet(
    onDismiss: () -> Unit,
    onPick: (skillId: String) -> Unit,
    presets: List<SkillRepository.Skill> = emptyList(),
) {
    // 只认 novel- 前缀的预设；按名字排一遍，保证每次打开顺序一致（仓库里 skills 的顺序随安装时间变）。
    val novelPresets = presets.filter { it.id.startsWith("novel-") }.sortedBy { it.name }

    StandardChatSheet(
        title = "新建小说项目",
        onDismiss = onDismiss,
        heightFraction = 0.6f,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(
                text = "选一个预设，AI 会帮你把整套目录和规则建好",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            if (novelPresets.isEmpty()) {
                Text(
                    text = "请先在技能里安装 novel-project",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(novelPresets, key = { it.id }) { skill ->
                        NovelPresetRow(
                            title = skill.name,
                            subtitle = skill.description,
                            onClick = { onPick(skill.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelPresetRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
