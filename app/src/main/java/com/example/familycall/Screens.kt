package com.example.familycall

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.UUID

private const val SOS_HOLD_MS = 3000

enum class SendState { Idle, Sending, Sent, Error, NotConfigured }

// ---------------------------------------------------------------- App shell

@Composable
fun FamilyCallApp(store: Store) {
    var tab by remember { mutableIntStateOf(0) }

    // Ask for everything once at startup (a caregiver should approve these).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.CALL_PHONE,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("📞", fontSize = 36.sp) }, label = { Text("Call") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = "Location",
                            tint = Color(0xFF1565C0),
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    label = { Text("Location") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("🆘", fontSize = 36.sp) }, label = { Text("SOS") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> CallScreen(store)
                1 -> LocationScreen(store)
                else -> SosScreen(store)
            }
        }
    }
}

// ---------------------------------------------------------------- Call tab

@Composable
fun CallScreen(store: Store) {
    val context = LocalContext.current
    val contacts = remember { mutableStateListOf<Contact>().apply { addAll(store.loadContacts()) } }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var confirmingContact by remember { mutableStateOf<Contact?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // Small on purpose: this is for the caregiver, not the user.
            Text(
                "⚙️", fontSize = 26.sp,
                modifier = Modifier.clickable { showSettings = true }.padding(8.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(contacts, key = { it.id }) { c ->
                ContactTile(
                    contact = c,
                    onTap = {
                        if (confirmingContact != null) return@ContactTile
                        if (store.confirmBeforeCall) {
                            confirmingContact = c
                        } else {
                            placeCall(context, c.phone)
                        }
                    },
                    onLongPress = {
                        if (confirmingContact != null) return@ContactTile
                        editing = c
                        showEditor = true
                    }
                )
            }
            item { AddTile { editing = null; showEditor = true } }
        }
    }

    confirmingContact?.let { contact ->
        ConfirmContactView(
            contact = contact,
            onYes = {
                confirmingContact = null
                placeCall(context, contact.phone)
            },
            onNo = {
                confirmingContact = null
            }
        )
    }

    if (showEditor) {
        ContactEditor(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                if (saved.sosCall) {
                    for (i in contacts.indices) {
                        if (contacts[i].id != saved.id && contacts[i].sosCall) {
                            contacts[i] = contacts[i].copy(sosCall = false)
                        }
                    }
                }
                val idx = contacts.indexOfFirst { it.id == saved.id }
                if (idx >= 0) contacts[idx] = saved else contacts.add(saved)
                store.saveContacts(contacts.toList())
                showEditor = false
            },
            onDelete = { c ->
                contacts.removeAll { it.id == c.id }
                store.saveContacts(contacts.toList())
                showEditor = false
            },
            onMove = { currentEdited, delta ->
                val idx = contacts.indexOfFirst { it.id == currentEdited.id }
                if (idx >= 0) {
                    val newIdx = idx + delta
                    if (newIdx in contacts.indices) {
                        if (currentEdited.sosCall) {
                            for (i in contacts.indices) {
                                if (contacts[i].id != currentEdited.id && contacts[i].sosCall) {
                                    contacts[i] = contacts[i].copy(sosCall = false)
                                }
                            }
                        }
                        contacts[idx] = currentEdited
                        val item = contacts.removeAt(idx)
                        contacts.add(newIdx, item)
                        store.saveContacts(contacts.toList())
                    }
                }
            }
        )
    }
    if (showSettings) SettingsDialog(store) { showSettings = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactTile(contact: Contact, onTap: () -> Unit, onLongPress: () -> Unit) {
    val bitmap = remember(contact.photoPath) {
        contact.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap, contentDescription = contact.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Text("👤", fontSize = 72.sp, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            contact.name,
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(8.dp)
        )
    }
}

@Composable
fun AddTile(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("➕", fontSize = 56.sp)
    }
}

@Composable
fun ContactEditor(
    initial: Contact?,
    onDismiss: () -> Unit,
    onSave: (Contact) -> Unit,
    onDelete: (Contact) -> Unit,
    onMove: ((current: Contact, delta: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var photoPath by remember { mutableStateOf(initial?.photoPath) }
    var shareLocation by remember { mutableStateOf(initial?.shareLocation ?: false) }
    var sosMessage by remember { mutableStateOf(initial?.sosMessage ?: false) }
    var sosCall by remember { mutableStateOf(initial?.sosCall ?: false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) savePhoto(context, uri)?.let { photoPath = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add person" else "Edit person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { shareLocation = !shareLocation }
                ) {
                    Checkbox(checked = shareLocation, onCheckedChange = { shareLocation = it })
                    Text("Location can be shared with this person")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sosMessage = !sosMessage }
                ) {
                    Checkbox(checked = sosMessage, onCheckedChange = { sosMessage = it })
                    Text("SOS: send message")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sosCall = !sosCall }
                ) {
                    Checkbox(checked = sosCall, onCheckedChange = { sosCall = it })
                    Text("SOS: call this person")
                }
                if (initial != null && onMove != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Order:")
                        Button(onClick = {
                            val current = Contact(id, name.trim(), phone.trim(), photoPath, shareLocation, sosMessage, sosCall)
                            onMove(current, -1)
                        }) { Text("◀") }
                        Button(onClick = {
                            val current = Contact(id, name.trim(), phone.trim(), photoPath, shareLocation, sosMessage, sosCall)
                            onMove(current, 1)
                        }) { Text("▶") }
                    }
                }
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (photoPath == null) "Choose photo" else "Change photo") }
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("Delete this person", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && phone.isNotBlank(),
                onClick = { onSave(Contact(id, name.trim(), phone.trim(), photoPath, shareLocation, sosMessage, sosCall)) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SettingsDialog(store: Store, onClose: () -> Unit) {
    var senderName by remember { mutableStateOf(store.senderName) }
    var confirmBeforeCall by remember { mutableStateOf(store.confirmBeforeCall) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings (for family)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    senderName, { senderName = it }, singleLine = true,
                    label = { Text("This person's name (shown in messages)") }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmBeforeCall = !confirmBeforeCall }
                ) {
                    Text("Ask before calling")
                    Switch(checked = confirmBeforeCall, onCheckedChange = { confirmBeforeCall = it })
                }
                Text(
                    "Tip: press and hold a picture on the Call screen to edit or delete it.",
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.senderName = senderName.trim()
                store.confirmBeforeCall = confirmBeforeCall
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- Location tab

@Composable
fun LocationScreen(store: Store) {
    val context = LocalContext.current
    val contacts = remember { store.loadContacts().filter { it.shareLocation } }
    var state by remember { mutableStateOf(SendState.Idle) }
    var activeContact by remember { mutableStateOf<Contact?>(null) }
    var confirmingContact by remember { mutableStateOf<Contact?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = Color(0xFF1565C0),
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
            )

            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📞 ➡️ ➕", fontSize = 48.sp, textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contacts, key = { it.id }) { c ->
                        ContactTile(
                            contact = c,
                            onTap = {
                                if (state != SendState.Idle || confirmingContact != null || activeContact != null) return@ContactTile
                                confirmingContact = c
                            },
                            onLongPress = { /* Long-press does nothing on this tab */ }
                        )
                    }
                }
            }
        }

        // Full-screen confirm view
        confirmingContact?.let { contact ->
            ConfirmContactView(
                contact = contact,
                onYes = {
                    val c = contact
                    confirmingContact = null
                    activeContact = c
                    state = SendState.Sending
                    fetchLocation(context) { loc ->
                        val ok = sendSms(context, listOf(c.phone), buildLocationMessage(store, loc))
                        state = if (ok) SendState.Sent else SendState.Error
                        feedback(context)
                    }
                },
                onNo = {
                    confirmingContact = null
                }
            )
        }

        // Status overlay
        activeContact?.let { contact ->
            ActionOverlay(
                state = state,
                contactName = contact.name,
                onTimeout = {
                    state = SendState.Idle
                    activeContact = null
                }
            )
        }
    }
}

// ---------------------------------------------------------------- SOS tab

@Composable
fun SosScreen(store: Store) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(SendState.Idle) }
    var holding by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(holding) {
        if (holding && state == SendState.Idle) {
            progress.animateTo(1f, tween(SOS_HOLD_MS, easing = LinearEasing))
            holding = false
            val allContacts = store.loadContacts()
            val msgContacts = allContacts.filter { it.sosMessage }
            val callContact = allContacts.firstOrNull { it.sosCall }

            if (msgContacts.isNotEmpty()) {
                state = SendState.Sending
                val numbers = msgContacts.map { it.phone }
                fetchLocation(context) { loc ->
                    val ok = sendSms(context, numbers, buildSosMessage(store, loc))
                    state = if (ok) SendState.Sent else SendState.Error
                    feedback(context)
                    if (callContact != null) {
                        placeCall(context, callContact.phone)
                    }
                }
            } else if (callContact != null) {
                state = SendState.Sent
                feedback(context)
                placeCall(context, callContact.phone)
            } else {
                state = SendState.NotConfigured
            }
        } else {
            progress.snapTo(0f)
        }
    }
    LaunchedEffect(state) {
        if (state == SendState.Sent || state == SendState.Error || state == SendState.NotConfigured) {
            delay(8000)
            state = SendState.Idle
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        BigRoundButton(
            color = when (state) {
                SendState.Sent -> Color(0xFF2E7D32)
                SendState.Error, SendState.NotConfigured -> Color(0xFF616161)
                else -> Color(0xFFC62828)
            },
            emoji = stateEmoji(state, idle = "🆘"),
            ringProgress = if (state == SendState.Idle) progress.value else null,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            }
        )
    }
}

// ---------------------------------------------------------------- Shared bits

private fun stateEmoji(state: SendState, idle: String) = when (state) {
    SendState.Idle -> idle
    SendState.Sending -> "⏳"
    SendState.Sent -> "✅"
    SendState.Error -> "❌"
    SendState.NotConfigured -> "⚙️"
}

@Composable
fun ConfirmContactView(
    contact: Contact,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    LaunchedEffect(contact) {
        delay(15000)
        onNo()
    }

    val bitmap = remember(contact.photoPath) {
        contact.photoPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectTapGestures { /* Block taps */ }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = contact.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("👤", fontSize = 100.sp)
                }
            }

            Text(
                text = contact.name,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Green ✔ (onYes)
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32))
                        .clickable(onClick = onYes),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✔", fontSize = 48.sp, color = Color.White)
                }

                // Red ✖ (onNo)
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFC62828))
                        .clickable(onClick = onNo),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✖", fontSize = 48.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun BigRoundButton(
    color: Color,
    emoji: String,
    ringProgress: Float?,
    modifier: Modifier = Modifier
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier
                .size(280.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 110.sp)
        }
        if (ringProgress != null && ringProgress > 0f) {
            CircularProgressIndicator(
                progress = { ringProgress },
                modifier = Modifier.size(300.dp),
                strokeWidth = 14.dp,
                color = Color.White
            )
        }
    }
}

@Composable
fun ActionOverlay(
    state: SendState,
    contactName: String,
    onTimeout: () -> Unit
) {
    if (state == SendState.Idle) return

    LaunchedEffect(state) {
        if (state == SendState.Sent || state == SendState.Error || state == SendState.NotConfigured) {
            delay(5000)
            onTimeout()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .pointerInput(Unit) {
                detectTapGestures { /* Block taps */ }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = stateEmoji(state, idle = ""),
                fontSize = 110.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = contactName,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
