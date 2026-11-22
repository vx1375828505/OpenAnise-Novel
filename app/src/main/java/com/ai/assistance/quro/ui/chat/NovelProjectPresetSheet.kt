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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.data.repository.SkillRepository

/** 开新书默认落在小说挂载目录下，和技能模板里的 `/var/minis/mounts/小说` 约定一致。 */
private const val NOVEL_PROJECT_ROOT = "/var/minis/mounts/小说"

/**
 * [T-novel-project-entry] 「新建小说项目」向导：第一步选预设，第二步一次填完开书信息，
 * 点「创建」后由调用方拼成完整指令写进输入框（不再让 AI 反问一遍）。
 *
 * 第三步只是回执页：指令已经落到输入框，用户可以直接发出；如果 AI 已经建好目录，
 * 这里给一个「打开项目目录」的入口，复用 ChatScreen 现有的文件浏览器回调
 * （onBrowseChatFolder），不新增导航路由。
 *
 * 外壳用 [StandardChatSheet]；系统返回键在非首步时回到上一步，首步才关闭面板。
 *
 * @param onCreate 用户点「创建」时回调，参数依次为预设技能 id 与表单内容。
 * @param onOpenFolder 打开指定 Linux 目录的文件浏览器（可缺省，缺省时回执页自动隐藏该按钮）。
 * @param presets 可选的预设技能；调用方从 SkillRepository 取快照传进来。
 */
@Composable
fun NovelProjectPresetSheet(
    onDismiss: () -> Unit,
    onCreate: (
        skillId: String,
        title: String,
        genre: String,
        platform: String,
        hero: String,
        targetDir: String,
    ) -> Unit,
    onOpenFolder: ((String) -> Unit)? = null,
    presets: List<SkillRepository.Skill> = emptyList(),
) {
    // 只认 novel-project 这一个预设。不能只判 `novel-` 前缀——父技能 novel-writing
    // 也是 novel- 开头，会被误列成「新建小说项目」的预设。
    // 按名字排一遍，保证每次打开顺序一致（仓库里 skills 的顺序随安装时间变）。
    val novelPresets = presets.filter { it.id == "novel-project" }.sortedBy { it.name }

    // 0 = 选预设，1 = 填表，2 = 已生成指令
    var step by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<SkillRepository.Skill?>(null) }

    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("") }
    var hero by remember { mutableStateOf("") }
    var targetDir by remember { mutableStateOf("$NOVEL_PROJECT_ROOT/") }
    // 用户自己动过目录就不再跟着书名同步，避免覆盖手工填写。
    var targetDirEdited by remember { mutableStateOf(false) }

    val sheetTitle = when (step) {
        1 -> "填写开书信息"
        2 -> "指令已生成"
        else -> "新建小说项目"
    }

    StandardChatSheet(
        title = sheetTitle,
        onDismiss = onDismiss,
        heightFraction = if (step == 0) 0.6f else 0.85f,
        onBack = {
            if (step > 0) {
                step -= 1
                true
            } else {
                false
            }
        },
    ) {
        when (step) {
            0 -> NovelPresetPicker(
                presets = novelPresets,
                onPick = { skill ->
                    picked = skill
                    step = 1
                },
            )

            1 -> NovelProjectForm(
                presetName = picked?.name.orEmpty(),
                title = title,
                genre = genre,
                platform = platform,
                hero = hero,
                targetDir = targetDir,
                onTitleChange = { raw ->
                    title = raw
                    if (!targetDirEdited) {
                        val trimmed = raw.trim()
                        targetDir = if (trimmed.isEmpty()) {
                            "$NOVEL_PROJECT_ROOT/"
                        } else {
                            "$NOVEL_PROJECT_ROOT/$trimmed"
                        }
                    }
                },
                onGenreChange = { genre = it },
                onPlatformChange = { platform = it },
                onHeroChange = { hero = it },
                onTargetDirChange = {
                    targetDir = it
                    targetDirEdited = true
                },
                onBack = {
                    step = 0
                    targetDirEdited = false
                },
                onCreate = {
                    val skillId = picked?.id
                    if (skillId != null) {
                        onCreate(
                            skillId,
                            title.trim(),
                            genre.trim(),
                            platform.trim(),
                            hero.trim(),
                            targetDir.trim(),
                        )
                        step = 2
                    }
                },
            )

            else -> NovelProjectDone(
                presetName = picked?.name.orEmpty(),
                title = title.trim(),
                targetDir = targetDir.trim(),
                onOpenFolder = onOpenFolder,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun NovelPresetPicker(
    presets: List<SkillRepository.Skill>,
    onPick: (SkillRepository.Skill) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            text = "选一个预设，下一步填好书信息，AI 直接按你的设定建库",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        if (presets.isEmpty()) {
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
                items(presets, key = { it.id }) { skill ->
                    NovelPresetRow(
                        title = skill.name,
                        subtitle = skill.description,
                        onClick = { onPick(skill) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelProjectForm(
    presetName: String,
    title: String,
    genre: String,
    platform: String,
    hero: String,
    targetDir: String,
    onTitleChange: (String) -> Unit,
    onGenreChange: (String) -> Unit,
    onPlatformChange: (String) -> Unit,
    onHeroChange: (String) -> Unit,
    onTargetDirChange: (String) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (presetName.isNotBlank()) {
                Text(
                    text = "预设：$presetName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            NovelFormField(
                value = title,
                onValueChange = onTitleChange,
                label = "书名",
                placeholder = "必填",
                modifier = Modifier.padding(top = 10.dp),
            )
            NovelFormField(
                value = genre,
                onValueChange = onGenreChange,
                label = "流派（选填）",
                placeholder = "都市修真 / 玄幻 / 悬疑…",
                modifier = Modifier.padding(top = 10.dp),
            )
            NovelFormField(
                value = platform,
                onValueChange = onPlatformChange,
                label = "平台（选填）",
                placeholder = "番茄 / 起点 / 七猫…",
                modifier = Modifier.padding(top = 10.dp),
            )
            NovelFormField(
                value = hero,
                onValueChange = onHeroChange,
                label = "男主名（选填）",
                placeholder = "留空则由 AI 提议",
                modifier = Modifier.padding(top = 10.dp),
            )
            NovelFormField(
                value = targetDir,
                onValueChange = onTargetDirChange,
                label = "目标目录",
                placeholder = NOVEL_PROJECT_ROOT,
                supporting = "建库位置，可手动修改",
                modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.size(12.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("上一步")
            }
            Button(
                onClick = onCreate,
                enabled = title.isNotBlank() && targetDir.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("创建")
            }
        }
    }
}

@Composable
private fun NovelProjectDone(
    presetName: String,
    title: String,
    targetDir: String,
    onOpenFolder: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "开书指令已填进输入框",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "发送后 AI 会先出建库清单等你确认，确认完再一次性建目录，不会再问一遍书名和设定。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (title.isNotBlank()) {
                Text(
                    text = "书名：《$title》",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (presetName.isNotBlank()) {
                Text(
                    text = "预设：$presetName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "目标目录：$targetDir",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (onOpenFolder != null) {
                Text(
                    text = "AI 建好后可点下面的按钮直接打开这个目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onOpenFolder != null) {
                OutlinedButton(
                    onClick = {
                        onOpenFolder(targetDir)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("打开项目目录")
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("去发送")
            }
        }
    }
}

@Composable
private fun NovelFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
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
