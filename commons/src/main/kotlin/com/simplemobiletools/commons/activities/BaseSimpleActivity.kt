package com.simplemobiletools.commons.activities

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.util.Pair
import com.simplemobiletools.commons.R
import com.simplemobiletools.commons.asynctasks.CopyMoveTask
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.ExportSettingsDialog
import com.simplemobiletools.commons.dialogs.FileConflictDialog
import com.simplemobiletools.commons.dialogs.WritePermissionDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.interfaces.CopyMoveListener
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.FileDirItem
import java.io.File
import java.util.*
import java.util.regex.Pattern

abstract class BaseSimpleActivity : AppCompatActivity() {
    var copyMoveCallback: ((destinationPath: String) -> Unit)? = null
    var actionOnPermission: ((granted: Boolean) -> Unit)? = null
    var isAskingPermissions = false
    var useDynamicTheme = true
    var checkedDocumentPath = ""

    private val GENERIC_PERM_HANDLER = 100

    companion object {
        var funAfterSAFPermission: ((success: Boolean) -> Unit)? = null
    }

    abstract fun getAppIconIDs(): ArrayList<Int>

    abstract fun getAppLauncherName(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        if (useDynamicTheme) {
            setTheme(getThemeId())
        }

        super.onCreate(savedInstanceState)
//        if (!packageName.startsWith("com.simplemobiletools.", true)) {
//            if ((0..50).random() == 10 || baseConfig.appRunCount % 100 == 0) {
//                val label = "You are using a fake version of the app. For your own safety download the original one from www.simplemobiletools.com. Thanks"
//                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {
//                    launchViewIntent("https://play.google.com/store/apps/dev?id=9070296388022589266")
//                }
//            }
//        }
    }

    override fun onResume() {
        super.onResume()
        if (useDynamicTheme) {
            setTheme(getThemeId())
            updateBackgroundColor()
        }
        updateActionbarColor()
        updateRecentsAppIcon()
        updateNavigationBarColor()
    }

    override fun onStop() {
        super.onStop()
        actionOnPermission = null
    }

    override fun onDestroy() {
        super.onDestroy()
        funAfterSAFPermission = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        if (newBase.baseConfig.useEnglish) {
            super.attachBaseContext(MyContextWrapper(newBase).wrap(newBase, "en"))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    fun updateBackgroundColor(color: Int = baseConfig.backgroundColor) {
        window.decorView.setBackgroundColor(color)
    }

    fun updateActionbarColor(color: Int = baseConfig.primaryColor) {
        supportActionBar?.setBackgroundDrawable(ColorDrawable(color))
        updateActionBarTitle(supportActionBar?.title.toString(), color)
        updateStatusbarColor(color)
        setTaskDescription(ActivityManager.TaskDescription(null, null, color))
    }

    fun updateStatusbarColor(color: Int) {
        window.statusBarColor = color.darkenColor()
    }

    fun updateNavigationBarColor(color: Int = baseConfig.navigationBarColor) {
        if (baseConfig.navigationBarColor != INVALID_NAVIGATION_BAR_COLOR) {
            try {
                window.navigationBarColor = color
            } catch (ignored: Exception) {
            }
        }
    }

    fun updateRecentsAppIcon() {
        if (baseConfig.isUsingModifiedAppIcon) {
            val appIconIDs = getAppIconIDs()
            val currentAppIconColorIndex = getCurrentAppIconColorIndex()
            if (appIconIDs.size - 1 < currentAppIconColorIndex) {
                return
            }

            val recentsIcon = BitmapFactory.decodeResource(resources, appIconIDs[currentAppIconColorIndex])
            val title = getAppLauncherName()
            val color = baseConfig.primaryColor

            val description = ActivityManager.TaskDescription(title, recentsIcon, color)
            setTaskDescription(description)
        }
    }

    fun updateMenuItemColors(menu: Menu?, useCrossAsBack: Boolean = false, baseColor: Int = baseConfig.primaryColor) {
        if (menu == null) {
            return
        }

        val color = baseColor.getContrastColor()
        for (i in 0 until menu.size()) {
            try {
                menu.getItem(i)?.icon?.setTint(color)
            } catch (ignored: Exception) {
            }
        }

        val drawableId = if (useCrossAsBack) R.drawable.ic_cross_vector else R.drawable.ic_arrow_left_vector
        val icon = resources.getColoredDrawableWithColor(drawableId, color)
        supportActionBar?.setHomeAsUpIndicator(icon)
    }

    private fun getCurrentAppIconColorIndex(): Int {
        val appIconColor = baseConfig.appIconColor
        getAppIconColors().forEachIndexed { index, color ->
            if (color == appIconColor) {
                return index
            }
        }
        return 0
    }

    fun setTranslucentNavigation() {
        window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        val partition = try {
            checkedDocumentPath.substring(9, 18)
        } catch (e: Exception) {
            ""
        }
        val sdOtgPattern = Pattern.compile(SD_OTG_SHORT)

        if (requestCode == OPEN_DOCUMENT_TREE && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            val isProperPartition = partition.isEmpty() || !sdOtgPattern.matcher(partition).matches() || (sdOtgPattern.matcher(partition).matches() && resultData.dataString!!.contains(partition))
            if (isProperSDFolder(resultData.data!!) && isProperPartition) {
                if (resultData.dataString == baseConfig.OTGTreeUri) {
                    toast(R.string.sd_card_usb_same)
                    return
                }

                saveTreeUri(resultData)
                funAfterSAFPermission?.invoke(true)
                funAfterSAFPermission = null
            } else {
                toast(R.string.wrong_root_selected)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, requestCode)
            }
        } else if (requestCode == OPEN_DOCUMENT_TREE_OTG && resultCode == Activity.RESULT_OK && resultData != null && resultData.data != null) {
            val isProperPartition = partition.isEmpty() || !sdOtgPattern.matcher(partition).matches() || (sdOtgPattern.matcher(partition).matches() && resultData.dataString!!.contains(partition))
            if (isProperOTGFolder(resultData.data!!) && isProperPartition) {
                if (resultData.dataString == baseConfig.treeUri) {
                    funAfterSAFPermission?.invoke(false)
                    toast(R.string.sd_card_usb_same)
                    return
                }
                baseConfig.OTGTreeUri = resultData.dataString!!
                baseConfig.OTGPartition = baseConfig.OTGTreeUri.removeSuffix("%3A").substringAfterLast('/').trimEnd('/')
                updateOTGPathFromPartition()

                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                applicationContext.contentResolver.takePersistableUriPermission(resultData.data!!, takeFlags)

                funAfterSAFPermission?.invoke(true)
                funAfterSAFPermission = null
            } else {
                toast(R.string.wrong_root_selected_usb)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, requestCode)
            }
        }
    }

    private fun saveTreeUri(resultData: Intent) {
        val treeUri = resultData.data
        baseConfig.treeUri = treeUri.toString()

        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        applicationContext.contentResolver.takePersistableUriPermission(treeUri!!, takeFlags)
    }

    private fun isProperSDFolder(uri: Uri) = isExternalStorageDocument(uri) && isRootUri(uri) && !isInternalStorage(uri)

    private fun isProperOTGFolder(uri: Uri) = isExternalStorageDocument(uri) && isRootUri(uri) && !isInternalStorage(uri)

    private fun isRootUri(uri: Uri) = DocumentsContract.getTreeDocumentId(uri).endsWith(":")

    private fun isInternalStorage(uri: Uri) = isExternalStorageDocument(uri) && DocumentsContract.getTreeDocumentId(uri).contains("primary")

    private fun isExternalStorageDocument(uri: Uri) = "com.android.externalstorage.documents" == uri.authority

    fun startAboutActivity(appNameId: Int, licenseMask: Int, versionName: String, faqItems: ArrayList<FAQItem>, showFAQBeforeMail: Boolean) {
        Intent(applicationContext, AboutActivity::class.java).apply {
            putExtra(APP_ICON_IDS, getAppIconIDs())
            putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
            putExtra(APP_NAME, getString(appNameId))
            putExtra(APP_LICENSES, licenseMask)
            putExtra(APP_VERSION_NAME, versionName)
            putExtra(APP_FAQ, faqItems)
            putExtra(SHOW_FAQ_BEFORE_MAIL, showFAQBeforeMail)
            startActivity(this)
        }
    }

    fun startCustomizationActivity() {
//        if (!packageName.contains("slootelibomelpmis".reversed(), true)) {
//            if (baseConfig.appRunCount > 100) {
//                val label = "You are using a fake version of the app. For your own safety download the original one from www.simplemobiletools.com. Thanks"
//                ConfirmationDialog(this, label, positive = R.string.ok, negative = 0) {
//                    launchViewIntent("https://play.google.com/store/apps/dev?id=9070296388022589266")
//                }
//                return
//            }
//        }

        Intent(applicationContext, CustomizationActivity::class.java).apply {
            putExtra(APP_ICON_IDS, getAppIconIDs())
            putExtra(APP_LAUNCHER_NAME, getAppLauncherName())
            startActivity(this)
        }
    }

    fun handleSAFDialog(path: String, callback: (success: Boolean) -> Unit): Boolean {
        return if (!packageName.startsWith("com.simplemobiletools")) {
            callback(true)
            false
        } else if (isShowingSAFDialog(path) || isShowingOTGDialog(path)) {
            funAfterSAFPermission = callback
            true
        } else {
            callback(true)
            false
        }
    }

    fun handleOTGPermission(callback: (success: Boolean) -> Unit) {
        if (baseConfig.OTGTreeUri.isNotEmpty()) {
            callback(true)
            return
        }

        funAfterSAFPermission = callback
        WritePermissionDialog(this, true) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (resolveActivity(packageManager) == null) {
                    type = "*/*"
                }

                if (resolveActivity(packageManager) != null) {
                    startActivityForResult(this, OPEN_DOCUMENT_TREE_OTG)
                } else {
                    toast(R.string.unknown_error_occurred)
                }
            }
        }
    }

    fun copyMoveFilesTo(fileDirItems: ArrayList<FileDirItem>, source: String, destination: String, isCopyOperation: Boolean, copyPhotoVideoOnly: Boolean,
                        copyHidden: Boolean, callback: (destinationPath: String) -> Unit) {
        if (source == destination) {
            toast(R.string.source_and_destination_same)
            return
        }

        if (!getDoesFilePathExist(destination)) {
            toast(R.string.invalid_destination)
            return
        }

        handleSAFDialog(destination) {
            copyMoveCallback = callback
            var fileCountToCopy = fileDirItems.size
            if (isCopyOperation) {
                startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
            } else {
                if (isPathOnOTG(source) || isPathOnOTG(destination) || isPathOnSD(source) || isPathOnSD(destination) || fileDirItems.first().isDirectory) {
                    handleSAFDialog(source) {
                        startCopyMove(fileDirItems, destination, isCopyOperation, copyPhotoVideoOnly, copyHidden)
                    }
                } else {
                    try {
                        checkConflicts(fileDirItems, destination, 0, LinkedHashMap()) {
                            toast(R.string.moving)
                            val updatedPaths = ArrayList<String>(fileDirItems.size)
                            val destinationFolder = File(destination)
                            for (oldFileDirItem in fileDirItems) {
                                var newFile = File(destinationFolder, oldFileDirItem.name)
                                if (newFile.exists()) {
                                    when {
                                        getConflictResolution(it, newFile.absolutePath) == CONFLICT_SKIP -> fileCountToCopy--
                                        getConflictResolution(it, newFile.absolutePath) == CONFLICT_KEEP_BOTH -> newFile = getAlternativeFile(newFile)
                                        else ->
                                            // this file is guaranteed to be on the internal storage, so just delete it this way
                                            newFile.delete()
                                    }
                                }

                                if (!newFile.exists() && File(oldFileDirItem.path).renameTo(newFile)) {
                                    if (!baseConfig.keepLastModified) {
                                        newFile.setLastModified(System.currentTimeMillis())
                                    }
                                    updatedPaths.add(newFile.absolutePath)
                                    deleteFromMediaStore(oldFileDirItem.path)
                                }
                            }

                            if (updatedPaths.isEmpty()) {
                                copyMoveListener.copySucceeded(false, fileCountToCopy == 0, destination)
                            } else {
                                runOnUiThread {
                                    copyMoveListener.copySucceeded(false, fileCountToCopy <= updatedPaths.size, destination)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        }
    }

    fun getAlternativeFile(file: File): File {
        var fileIndex = 1
        var newFile: File?
        do {
            val newName = String.format("%s(%d).%s", file.nameWithoutExtension, fileIndex, file.extension)
            newFile = File(file.parent, newName)
            fileIndex++
        } while (getDoesFilePathExist(newFile!!.absolutePath))
        return newFile
    }

    private fun startCopyMove(files: ArrayList<FileDirItem>, destinationPath: String, isCopyOperation: Boolean, copyPhotoVideoOnly: Boolean, copyHidden: Boolean) {
        val availableSpace = destinationPath.getAvailableStorageB()
        val sumToCopy = files.sumByLong { it.getProperSize(applicationContext, copyHidden) }
        if (availableSpace == -1L || sumToCopy < availableSpace) {
            checkConflicts(files, destinationPath, 0, LinkedHashMap()) {
                toast(if (isCopyOperation) R.string.copying else R.string.moving)
                val pair = Pair(files, destinationPath)
                CopyMoveTask(this, isCopyOperation, copyPhotoVideoOnly, it, copyMoveListener, copyHidden).execute(pair)
            }
        } else {
            val text = String.format(getString(R.string.no_space), sumToCopy.formatSize(), availableSpace.formatSize())
            toast(text, Toast.LENGTH_LONG)
        }
    }

    fun checkConflicts(files: ArrayList<FileDirItem>, destinationPath: String, index: Int, conflictResolutions: LinkedHashMap<String, Int>,
                       callback: (resolutions: LinkedHashMap<String, Int>) -> Unit) {
        if (index == files.size) {
            callback(conflictResolutions)
            return
        }

        val file = files[index]
        val newFileDirItem = FileDirItem("$destinationPath/${file.name}", file.name, file.isDirectory)
        if (getDoesFilePathExist(newFileDirItem.path)) {
            FileConflictDialog(this, newFileDirItem, files.size > 1) { resolution, applyForAll ->
                if (applyForAll) {
                    conflictResolutions.clear()
                    conflictResolutions[""] = resolution
                    checkConflicts(files, destinationPath, files.size, conflictResolutions, callback)
                } else {
                    conflictResolutions[newFileDirItem.path] = resolution
                    checkConflicts(files, destinationPath, index + 1, conflictResolutions, callback)
                }
            }
        } else {
            checkConflicts(files, destinationPath, index + 1, conflictResolutions, callback)
        }
    }

    fun handlePermission(permissionId: Int, callback: (granted: Boolean) -> Unit) {
        actionOnPermission = null
        if (hasPermission(permissionId)) {
            callback(true)
        } else {
            isAskingPermissions = true
            actionOnPermission = callback
            ActivityCompat.requestPermissions(this, arrayOf(getPermissionString(permissionId)), GENERIC_PERM_HANDLER)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        isAskingPermissions = false
        if (requestCode == GENERIC_PERM_HANDLER && grantResults.isNotEmpty()) {
            actionOnPermission?.invoke(grantResults[0] == 0)
        }
    }

    val copyMoveListener = object : CopyMoveListener {
        override fun copySucceeded(copyOnly: Boolean, copiedAll: Boolean, destinationPath: String) {
            if (copyOnly) {
                toast(if (copiedAll) R.string.copying_success else R.string.copying_success_partial)
            } else {
                toast(if (copiedAll) R.string.moving_success else R.string.moving_success_partial)
            }

            copyMoveCallback?.invoke(destinationPath)
            copyMoveCallback = null
        }

        override fun copyFailed() {
            toast(R.string.copy_move_failed)
            copyMoveCallback = null
        }
    }

    fun checkAppOnSDCard() {
        if (!baseConfig.wasAppOnSDShown && isAppInstalledOnSDCard()) {
            baseConfig.wasAppOnSDShown = true
            ConfirmationDialog(this, "", R.string.app_on_sd_card, R.string.ok, 0) {}
        }
    }

    fun exportSettings(configItems: LinkedHashMap<String, Any>, defaultFilename: String) {
        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                ExportSettingsDialog(this, defaultFilename) {
                    val file = File(it)
                    val fileDirItem = FileDirItem(file.absolutePath, file.name)
                    getFileOutputStream(fileDirItem, true) {
                        if (it == null) {
                            toast(R.string.unknown_error_occurred)
                            return@getFileOutputStream
                        }

                        ensureBackgroundThread {
                            it.bufferedWriter().use { out ->
                                for ((key, value) in configItems) {
                                    out.writeLn("$key=$value")
                                }
                            }

                            toast(R.string.settings_exported_successfully)
                        }
                    }
                }
            }
        }
    }
}
