package com.example.ownscan.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.example.ownscan.R

/**
 * Central repository for all in-app icons used across ScanVault.
 */
object AppIcons {

    // -------------------------------------------------------------
    // Compose Material ImageVector Icons
    // -------------------------------------------------------------

    // Top app bar - Navigate back
    val ArrowBack: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

    // Top app bar - More options overflow menu
    val MoreVert: ImageVector = Icons.Default.MoreVert

    // Selection bar - Clear / close selection mode
    val Close: ImageVector = Icons.Default.Close

    // Doc detail - Add photo secondary FAB
    val AddPhoto: ImageVector = Icons.Default.AddPhotoAlternate

    // Doc detail - Camera scan primary action FAB
    val Camera: ImageVector = Icons.Default.CameraAlt

    // Doc detail - Save completion success checkmark indicator
    val Check: ImageVector = Icons.Default.Check

    // Settings screen - Send Feedback row
    val Feedback: ImageVector = Icons.Outlined.Feedback

    // Settings screen - Report Bug row
    val BugReport: ImageVector = Icons.Outlined.BugReport

    // -------------------------------------------------------------
    // In-App Navigation & Core Screen Icons
    // -------------------------------------------------------------

    // Bottom nav - Home (outline = unselected, filled = selected)
    val NavHomeOutline: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_home_outline)

    // Bottom nav - Home (outline = unselected, filled = selected)
    val NavHomeFilled: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_home_filled)

    // Bottom nav - Files (outline = unselected, filled = selected)
    val NavFilesOutline: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_files_outline)

    // Bottom nav - Files (outline = unselected, filled = selected)
    val NavFilesFilled: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_files_filled)

    // Bottom nav - Tools (outline = unselected, filled = selected)
    val NavToolsOutline: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_tools_outline)

    // Bottom nav - Tools (outline = unselected, filled = selected)
    val NavToolsFilled: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_tools_filled)

    // Bottom nav - Settings (outline = unselected, filled = selected)
    val NavSettingsOutline: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_settings_outline)

    // Bottom nav - Settings (outline = unselected, filled = selected)
    val NavSettingsFilled: Painter
        @Composable
        get() = painterResource(R.drawable.ic_nav_settings_filled)

    // -------------------------------------------------------------
    // Vector Drawable Painters (Res-based)
    // -------------------------------------------------------------

    // Folder representation (Doc cards, files list, move/copy dialog, folder page)
    val FolderVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_folder)

    // Document & folder locked state, lock actions, security banners & settings
    val LockVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_lock)

    // Document & folder unlock action, temporarily unlocked session badge, decrypt dialogs
    val UnlockVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_unlock)

    // Document menu delete action, trash screen delete actions
    val DeleteVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_delete)

    // Document menu edit action, page editor, Image viewer - Edit image button
    val EditVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_edit)

    // Document menu rename action, folder rename action
    val RenameVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_rename)

    // Document menu favorite toggle action, document favorite badge
    val FavoriteVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_favorite)

    // Document menu backup to Drive action
    val BackupDriveVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_backup_drive)

    // File status - Cloud backup completed
    val BackupDone: Painter
        @Composable
        get() = painterResource(R.drawable.ic_status_backup_done)

    // Document menu save to device action
    val SaveDeviceVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_save_device)

    // Document menu set expiry date action, expiry date picker
    val ExpiryVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_expiry)

    // Expiring soon document badge, timer alert indicator
    val TimerVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_timer)

    // Document menu tag action, tag filter & edit dialogs
    val TagVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_tag)

    // Document menu share action, share sheets
    val ShareVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_share)

    // Document menu copy OCR text action, duplicate page action
    val CopyVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_copy)

    // Document menu file info action, info banners & notices
    val InfoVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_info)

    // Document menu move to folder action, multi-select move action
    val MoveVector: Painter
        @Composable
        get() = painterResource(R.drawable.ic_action_move)

    // -------------------------------------------------------------
    // Image Editor Tool Vector Painters
    // -------------------------------------------------------------

    // Image editor - Filter tool
    val EditorFilter: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_filter)

    // Image editor - Crop tool
    val EditorCrop: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_crop)

    // Image editor - Pen / Draw tool
    val EditorPen: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_pen)

    // Image editor - Highlight tool
    val EditorHighlight: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_highlight)

    // Image editor - Eraser tool
    val EditorEraser: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_eraser)

    // Image editor - Signature tool
    val EditorSignature: Painter
        @Composable
        get() = painterResource(R.drawable.ic_editor_signature)

    // Bottom nav - Home tab
    const val Home: String = "🏠"

    // Bottom nav - Files / All documents tab
    const val AllFiles: String = "📁"

    // Bottom nav - Tools tab
    const val Tools: String = "🛠️"

    // Bottom nav - Settings tab
    const val Settings: String = "⚙️"

    // Top bar / Profile button (Home, Files, Tools screen header)
    const val Profile: String = "👤"

    // Search button (Home and Files top bars, Gallery viewer)
    const val Search: String = "🔍"

    // Back navigation chevron button (Files screen folder header, Trash screen top bar)
    const val BackChevron: String = "❮"

    // Close / Dismiss glyph icon button (Dialogs, Banners, Search clear, Editor close)
    const val CloseGlyph: String = "✕"

    // Doc card - Overflow options menu button
    const val OverflowMenu: String = "⋮"

    // -------------------------------------------------------------
    // Tool Cards Icons
    // -------------------------------------------------------------

    // Tool cards - Compress File tool
    const val Compress: String = "🗜️"

    // Tool cards and multi-selection action - Merge PDF tool
    const val Merge: String = "📑"

    // Tool cards and export dialog - Apply Watermark tool
    const val Watermark: String = "🌊"

    // Tool cards - PDF to JPEG converter tool
    const val PdfToJpeg: String = "🖼️"

    // Tool cards - Image to PDF converter tool
    const val ImageToPdf: String = "📄"

    // -------------------------------------------------------------
    // Document, Folder & File Type Icons
    // -------------------------------------------------------------

    // Folder representation (doc cards, move/copy dialog, files list)
    const val Folder: String = "📁"

    // Trash / Delete (Trash screen empty state, retention dialog, doc action menus)
    const val Trash: String = "🗑️"

    // Document / PDF file type indicator and thumbnail placeholder
    const val FilePdf: String = "📄"

    // Image / JPG file type indicator
    const val FileImage: String = "🖼️"

    // -------------------------------------------------------------
    // Document Actions & Menu Item Icons
    // -------------------------------------------------------------

    // Document favorite - Starred (Doc card badge and toggle menu)
    const val Star: String = "★"

    // Document favorite - Unstarred outline (Doc card menu toggle)
    const val StarOutline: String = "☆"

    // Cloud / Google Drive backup indicator and action
    const val Cloud: String = "☁️"

    // Save to device / export action
    const val Save: String = "💾"

    // Expiry date picker and banner
    const val Calendar: String = "📅"

    // Document tag action and dialog
    const val Tag: String = "🏷️"

    // Share document action
    const val Share: String = "📤"

    // Share target import prompt
    const val Import: String = "📥"

    // Rename / edit action
    const val Edit: String = "✏️"

    // Document action - Copy OCR text
    const val Clipboard: String = "📋"

    // Document action - Archive / Move to folder
    const val BoxArchive: String = "📦"

    // Document action and Image editor tool - Filters
    const val Filters: String = "🎨"

    // -------------------------------------------------------------
    // Security & Lock Icons
    // -------------------------------------------------------------

    // Document locked / Protected document indicator and lock action
    const val Lock: String = "🔒"

    // Document unlocked indicator and unlock toggle action
    const val Unlock: String = "🔓"

    // ID card detection banner and screen security settings
    const val Shield: String = "🛡️"

    // Encrypted backup header
    const val KeySecurity: String = "🔐"

    // Password field visibility toggle - visible
    const val Visibility: String = "👁️"

    // Password field visibility toggle - hidden
    const val VisibilityOff: String = "🙈"

    // -------------------------------------------------------------
    // Editor & Annotation Tool Icons
    // -------------------------------------------------------------

    // Image editor - Crop tool
    const val Crop: String = "✂️"

    // Image editor - Highlight tool
    const val Highlight: String = "🖍️"

    // Image editor - Eraser tool
    const val Eraser: String = "🧽"

    // Image editor and Signature engine - Signature tool
    const val Signature: String = "✍️"

    // Signature pad - Undo stroke action
    const val Undo: String = "↩️"

    // -------------------------------------------------------------
    // Status, Smart Collections & Notification Icons
    // -------------------------------------------------------------

    // Expiring soon warning badge (Doc card, Doc detail, Smart collection chip)
    const val Hourglass: String = "⏳"

    // Smart collection chip - Receipts category
    const val Receipt: String = "🧾"

    // Smart collection chip - IDs category
    const val IdCard: String = "🪪"

    // Smart collection chip - Recent files category
    const val Clock: String = "🕒"

    // Settings screen - Dark theme selector
    const val ThemeDark: String = "🌙"

    // Settings screen - Light theme selector
    const val ThemeLight: String = "☀️"

    // Theme selector - Selected checkmark glyph
    const val CheckmarkGlyph: String = "✓"

    // Trash screen retention notice information icon
    const val Info: String = "ℹ️"

    // Warning confirmation dialog icon
    const val Warning: String = "⚠️"

    // Compression size arrow indicator
    const val ArrowRight: String = "➔"

    // Expiry manager date picker header
    const val CalendarSchedule: String = "🗓️"

    // What's new dialog header
    const val Sparkle: String = "✨"

    // App widget - Scan action
    const val WidgetCamera: String = "📷"

    // App widget - Files action
    const val WidgetFolder: String = "📂"
}
