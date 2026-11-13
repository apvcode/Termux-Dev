package com.apvcode.devx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.apvcode.devx.R

/** Tabler Icons (MIT, see NOTICE) as vector drawables. One set, one stroke width. */
object DevxIcons {
    /** The list of chats. */
    val ChatList: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_list)

    /** A new chat. */
    val ChatPlus: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_plus)

    /** A chat where the assistant has answered (unread). */
    val ChatAlert: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_alert)

    /** A chat where the assistant asks you a question. */
    val ChatQuestion: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_question)

    /** Search through chats. */
    val ChatSearch: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_search)

    /** A project. */
    val Folder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_folder)

    /** An expanded project. */
    val FolderOpen: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_folder_open)

    /** Delete a project (crossed out). */
    val FolderOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_folder_off)

    /** Add a chat to a project. */
    val FolderPlus: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_folder_plus)

    /** Delete a chat (crossed out). */
    val ChatOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chat_off)

    /** MCP servers. */
    val Mcp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mcp)

    /** Pin. */
    val Pin: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pin)

    /** Rename. */
    val Pencil: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_pencil)

    /** Archive. */
    val Download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_download)
    val Minimize: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_minimize)
    val Archive: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_archive)

    /** Opens a submenu. */
    val ChevronRight: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron_right)

    /** Back inside a menu. */
    val ArrowLeft: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_left)

    /** Selected. */
    val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_check)

    /** Copy. */
    val Copy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_copy)

    /** Copy, with the check mark it turns into once the text is copied. */
    val CopyCheck: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_copy_check)

    /** Regenerate the answer / reload a list. */
    val Refresh: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_refresh)

    /** Attach menu: a photo from the gallery, a file, a picture from the camera. */
    val Photo: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_photo)
    val FilePlus: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_plus)
    val Camera: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_camera)

    /** Temporary chat, main menu, "about". */
    val MessageCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_message_circle)
    val Menu4: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_menu_4)
    val Eye: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_eye)

    /** The chat menu. */
    val Lock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock)
    val LockOpen: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock_open)
    val Paperclip: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_paperclip)
    val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_search)
    val Home: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_home)
    val ChevronUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron_up)
    val ChevronDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron_down)

    /** The globe, for the sites an answer was built from. */
    val World: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_world_www)

    /** Select text. */
    val FileText: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_text)

    /** Share. */
    val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_share)

    /** Settings. */
    val Settings: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)

    /** Stop generating (filled). */
    val Stop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_stop)

    /** Memory: a notebook. */
    val Notebook: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_notebook)

    /** The model's thinking. */
    val Brain: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_brain)

    val Desktop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_desktop)

    val Mobile: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_mobile)

    val Qrcode: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_qrcode)

    val BrandGithub: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_brand_github)
    val Book: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_book)
    val Sitemap: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sitemap)
    val Fingerprint: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_fingerprint)
    val Backspace: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_backspace)
    val Unlink: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_unlink)
    val Plug: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_plug)

    val Wifi: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_wifi)

    val Cloud: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cloud)

    val Terminal: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_terminal)

    val Scan: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_scan)

    val Shield: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_shield)
    val ShieldLock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_shield_lock)

    val Slash: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_slash)

    val ListCheck: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_list_check)

    val Cpu: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_cpu)

    val Gauge: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_gauge)

    val Undo: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_arrow_back_up)

    val Loader: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_loader)
}
