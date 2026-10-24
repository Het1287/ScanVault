# Extracted Snippets — ID Card Mode (Front & Back on Single A4)

The following code snippets were extracted from shared files before removal.

---

### 1. `app/src/main/res/values/strings.xml`
Lines 39-44:
```xml
    <!-- ID Card Strings -->
    <string name="id_card_mode_title">ID Card Mode</string>
    <string name="id_card_mode_description">Combine Front &amp; Back of an ID onto a single A4 page with standard 85.6 x 54 mm physical dimensions. Aspect ratios are preserved without stretching.</string>
    <string name="pick_front">+ Pick Front</string>
    <string name="pick_back">+ Pick Back</string>
```

---

### 2. `app/src/main/java/com/example/ScanVault/MainApp.kt`
State field (around line 90):
```kotlin
    var showIdCardDialog by remember { mutableStateOf(false) }
```

Dialog invocation (around lines 580-591):
```kotlin
    if (showIdCardDialog) {
        IdCardModeDialog(
            activity = activity,
            targetFolder = activeFolder,
            onDismiss = { showIdCardDialog = false },
            onSavedSuccessfully = {
                showIdCardDialog = false
                refreshDocs()
                triggerAutoSyncIfEnabled()
            }
        )
    }
```

Trigger callbacks passed to screens:
- Line 1025 in `FilesScreenDesign`:
```kotlin
onIdCardTrigger = { showIdCardDialog = true },
```
- Line 1065 in `ToolsScreenDesign`:
```kotlin
onIdCardTrigger = { showIdCardDialog = true },
```

---

### 3. `app/src/main/java/com/example/ScanVault/FilesScreen.kt`
Parameter signature:
```kotlin
onIdCardTrigger: () -> Unit = {},
```

Action Bar Button (lines 698-705):
```kotlin
    Button(
        onClick = onIdCardTrigger,
        colors = ButtonDefaults.buttonColors(containerColor = colors.cardSubtle),
        modifier = Modifier.weight(1.1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Text("🪪 ID Card", fontSize = 11.sp, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
    }
```

---

### 4. `app/src/main/java/com/example/ScanVault/ToolsScreen.kt`
Parameter signature:
```kotlin
onIdCardTrigger: () -> Unit = {},
```
