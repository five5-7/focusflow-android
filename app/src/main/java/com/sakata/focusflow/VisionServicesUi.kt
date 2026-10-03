package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun SecureSharedKeyEditor(settings: TutorialSearchSettings, onChanged: (TutorialSearchSettings) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    Text(if(settings.apiKey.isNotBlank()) "共享 key 已保存（不回显）" else "共享 key 未保存或暂不可读")
    OutlinedTextField(value=input,onValueChange={ input=it }, label={ Text("替换硅基流动共享 key") }, singleLine=true, visualTransformation=PasswordVisualTransformation(), modifier=Modifier.fillMaxWidth(),enabled=!busy)
    fun save(value: String) { busy=true; scope.launch { val ok=withContext(Dispatchers.IO) { VisionCredentialStore(context).saveShared(value) }; input=""; busy=false; message=if(ok) "已保存；视觉能力测试需重新执行" else "未确认保存成功，请重试；旧数据未被清空"; if(ok) onChanged(withContext(Dispatchers.IO) { PrototypeStore(context).loadTutorialSearchSettings() }) } }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(enabled=!busy && input.isNotBlank(),onClick={ save(input) }) { Text("保存替换") }
        TextButton(enabled=!busy,onClick={ save("") }) { Text("删除共享 key") }
    }
    Text("共享 key 同时用于硅基流动视觉识别与学习建议；删除后两者都需重新填写。",style=MaterialTheme.typography.bodySmall)
    message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun VisionServicesSettings(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onSharedKeyChanged: (TutorialSearchSettings) -> Unit) {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    val store=remember { VisionServiceStore(context) }; val vault=remember { VisionCredentialStore(context) }
    var configuration by remember { mutableStateOf<VisionServiceConfiguration?>(null) }
    var available by remember { mutableStateOf<Set<String>>(emptySet()) }
    var keyStates by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var message by remember { mutableStateOf("正在读取服务配置…") }
    var editing by remember { mutableStateOf<VisionServiceProfile?>(null) }
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("https://") }; var model by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf("60") }; var busy by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<VisionSession?>(null) }
    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    suspend fun reload() {
        val loaded=withContext(Dispatchers.IO) {
            vault.migrateShared(); val read=store.read()
            val keys=(read as? VisionConfigurationRead.Ready)?.configuration?.profiles.orEmpty().associate { p -> p.id to when(vault.read(p.credentialRef)) { is VisionCredentialRead.Ready -> "key 已保存"; VisionCredentialRead.Missing -> "key 未保存"; VisionCredentialRead.Uncertain -> "key 写入未确认"; else -> "key 不可读，请重填" } }
            Triple(read,store.verifiedProfiles(vault).map { it.id }.toSet(),keys)
        }
        configuration=(loaded.first as? VisionConfigurationRead.Ready)?.configuration; available=loaded.second;keyStates=loaded.third
        if(configuration==null) message="配置损坏或写入未确认；保留原数据，不能覆盖为空配置。"
    }
    LaunchedEffect(Unit) { reload(); if(configuration!=null) message="先保存配置，再点击能力测试。测试使用生成的小图片，可能产生 API 费用。" }
    DisposableEffect(Unit) { onDispose { session?.cancel() } }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("截图识别（可选联网）"); Switch(checked=enabled,onCheckedChange=onEnabledChange) }
    Text("仅支持 OpenAI 兼容 Chat Completions；填写接口根地址与视觉模型。保存 key 不回显，不会在启动时自动测试。",style=MaterialTheme.typography.bodySmall)
    Text(message,style=MaterialTheme.typography.bodySmall)
    if(configuration==null) TextButton(onClick={ scope.launch { val ok=withContext(Dispatchers.IO) { store.retryPendingWrite() }; reload(); message=if(ok) "已确认配置保存" else "仍未确认；损坏原文保留" } }) { Text("重试未确认的保存") }
    configuration?.profiles?.forEach { p ->
        HorizontalDivider()
        Text("${p.name} · ${p.baseUrl}",style=MaterialTheme.typography.titleSmall)
        Text("${keyStates[p.id].orEmpty()} · ${p.model} · ${if(p.id in available) "已通过能力测试" else "待测试或凭据不可读"}${if(configuration?.defaultId==p.id && p.id in available) " · 默认" else ""}",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(enabled=!busy,onClick={ editing=p;name=p.name;url=p.baseUrl;model=p.model;timeout=p.timeoutSeconds.toString();key="";modelList=emptyList() }) { Text("编辑") }
            TextButton(enabled=!busy,onClick={
                val task=VisionSession(); session=task; busy=true
                scope.launch {
                    val result=withContext(Dispatchers.IO) { val credential=vault.read(p.credentialRef) as? VisionCredentialRead.Ready; if(credential==null) "请先填写可读的 key" else { val probe=VisionServiceClient().probe(p,credential.secret,task); if(!task.cancelled()) { if(store.recordProbe(p,credential.revision,probe,vault)) probe.message else "配置已变化或保存未确认，测试结果未保存" } else "已取消，测试结果未保存" } }
                    if(session===task) { message=result; busy=false; session=null; reload() }
                }
            }) { Text("能力测试") }
            TextButton(enabled=!busy && p.id in available,onClick={ scope.launch { val ok=withContext(Dispatchers.IO) { store.setDefault(p.id,vault) }; reload(); message=if(ok) "已设为默认；导入时仍会确认上传目标" else "未保存：配置或凭据已变化" } }) { Text("设默认") }
        }
    }
    if(busy) TextButton(onClick={ session?.cancel();session=null;busy=false;message="已取消，旧结果不会写入" }) { Text("取消请求") }
    TextButton(enabled=!busy && configuration!=null,onClick={ editing=null;name="";url="https://";model="";timeout="60";key="";modelList=emptyList() }) { Text("新增服务（下方填写）") }
    if(configuration!=null) {
        OutlinedTextField(name,{name=it},label={Text("服务名称")},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true)
        OutlinedTextField(url,{url=it},label={Text("HTTPS Base URL，例如 https://域名/v1")},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true)
        OutlinedTextField(model,{model=it},label={Text("视觉模型 ID（可手填）")},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true)
        OutlinedTextField(timeout,{timeout=it},label={Text("响应超时秒数（5–120）")},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true)
        OutlinedTextField(key,{key=it},label={Text("key：留空保留已存值，填写后替换")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true)
        Text("编辑硅基流动时使用共享 key，替换或删除会影响学习建议。更换地址应新增服务并填写该服务自己的 key。",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(enabled=!busy,onClick={
                val expected=configuration ?: return@TextButton; val old=editing
                val id=old?.id ?: UUID.randomUUID().toString()
                val p=VisionServiceProfile(id,name.trim(),url.trim().trimEnd('/'),model.trim(),old?.credentialRef ?: "vision-$id",old?.revision ?: UUID.randomUUID().toString(),timeout.toIntOrNull() ?: 0)
                if(p.rejection()!=null) { message=p.rejection()!!; return@TextButton }
                val entered=key;busy=true
                scope.launch {
                    val ok=withContext(Dispatchers.IO) { synchronized(VisionCredentialStore.lock) { val keyOk=entered.isBlank() || if(p.credentialRef==VisionCredentialStore.SHARED_REF) vault.saveShared(entered) else vault.save(p.credentialRef,entered); keyOk && store.saveProfile(expected,p) } }
                    key="";busy=false;reload();editing=configuration?.profiles?.find { it.id==id };message=if(ok) "配置已保存；更改模型、地址或 key 后请重新测试" else "未确认保存，请检查只读状态或配置变化后重试"
                    if(ok && p.credentialRef==VisionCredentialStore.SHARED_REF) onSharedKeyChanged(withContext(Dispatchers.IO) { PrototypeStore(context).loadTutorialSearchSettings() })
                }
            }) { Text("保存配置") }
            editing?.let { p ->
                TextButton(enabled=!busy,onClick={ scope.launch { val ok=withContext(Dispatchers.IO) { if(p.credentialRef==VisionCredentialStore.SHARED_REF) vault.saveShared("") else vault.remove(p.credentialRef) }; reload();message=if(ok) "key 已删除" else "未确认删除，请重试";if(ok && p.credentialRef==VisionCredentialStore.SHARED_REF) onSharedKeyChanged(withContext(Dispatchers.IO) { PrototypeStore(context).loadTutorialSearchSettings() }) } }) { Text("删除 key") }
                TextButton(enabled=!busy,onClick={ scope.launch { val expected=configuration!!; val ok=withContext(Dispatchers.IO) { store.delete(expected,p.id) };reload();if(ok) { editing=null;name="";url="https://";model="" };message=if(ok) "服务已删除；共享 key 保留。独立 key 可先用删除 key 移除。" else "删除未确认" } }) { Text("删除服务") }
            }
        }
        editing?.let { p ->
            TextButton(enabled=!busy,onClick={ val task=VisionSession(); session=task;busy=true;scope.launch { val ids=withContext(Dispatchers.IO) { val k=vault.read(p.credentialRef) as? VisionCredentialRead.Ready; if(k==null) null else VisionServiceClient().models(p,k.secret,task) };if(session===task) { busy=false;session=null;modelList=ids.orEmpty().take(100);message=if(ids==null) "模型列表不可用，可以手填，不影响能力测试" else "已获取模型列表，选择后须保存并测试" } } }) { Text("可选：获取模型列表") }
        }
        modelList.take(20).forEach { id -> TextButton(enabled=!busy,onClick={model=id}) { Text(id) } }
    }
}

@Composable
internal fun VisionServiceChoiceDialog(profiles: List<VisionServiceProfile>, defaultId: String?, onChoose: (VisionServiceProfile) -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismissRequest=onDismiss,title={Text("选择截图上传服务")},text={ Column {
        Text("接下来选择的课表图片会发送到所选地址，可能产生 API 费用。识别结果仍需确认。")
        Text("列表包含完整能力通过，或仅连接和结构化输出通过的服务；后者仅用于手动逐条审核，不会自动导入，也不能设为默认。")
        if(profiles.isEmpty()) Text("没有可审核的服务，请先到设置保存并测试。")
        profiles.forEach { p -> TextButton(onClick={onChoose(p)}) { Text("${p.name}${if(p.id==defaultId) "（默认）" else ""}\n${p.baseUrl}\n${p.model}") } }
    } },confirmButton={},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
