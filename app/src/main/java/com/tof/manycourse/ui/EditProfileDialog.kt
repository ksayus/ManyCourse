package com.tof.manycourse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.ProfileRepository
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.GlassOverlay
import com.tof.manycourse.ui.components.GlassPanel
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 编辑资料对话框（玻璃浮层）：昵称（必填）+ 专业/年级（选填）。
 * 与添加课程一致：面板为静态高不透明度表面，进出场动画由 GlassOverlay 统一处理。
 */
@Composable
fun EditProfileDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf(ProfileRepository.nickname.value) }
    var major by remember { mutableStateOf(ProfileRepository.major.value) }
    var touched by remember { mutableStateOf(false) }
    val nicknameError = touched && nickname.isBlank()

    // 浮层常驻组合（为了播退出动画），每次打开时同步一次资料，避免显示过期值
    LaunchedEffect(visible) {
        if (visible) {
            nickname = ProfileRepository.nickname.value
            major = ProfileRepository.major.value
            touched = false
        }
    }

    GlassOverlay(visible = visible, onDismissRequest = onDismiss) {
        GlassPanel(
            cornerRadius = 24,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "编辑资料",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                Spacer(Modifier.height(16.dp))

                FormLabel("显示昵称")
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = nicknameError,
                    placeholder = { Text("输入昵称", fontSize = 15.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                )
                if (nicknameError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "请输入昵称",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))

                FormLabel("专业 / 年级")
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = major,
                    onValueChange = { major = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("例如：计算机科学 · 2022级", fontSize = 15.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = dialogFieldColors(),
                )

                Spacer(Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CampusButton(
                        text = "保存",
                        enabled = nickname.isNotBlank(),
                        onClick = {
                            touched = true
                            if (nickname.isBlank()) return@CampusButton
                            ProfileRepository.nickname.value = nickname.trim()
                            if (major.isNotBlank()) ProfileRepository.major.value = major.trim()
                            onDismiss()
                        },
                    )
                    CampusButton(
                        text = "取消",
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    errorBorderColor = MaterialTheme.colorScheme.error,
    // 同添加课程：输入框用 fieldTint 令牌，与面板底色拉开一档
    focusedContainerColor = LocalGlassTokens.current.fieldTint,
    unfocusedContainerColor = LocalGlassTokens.current.fieldTint,
)

@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
