package com.tuya.smart.iotgateway.speech

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SparseArray
import android.view.WindowManager
import pub.devrel.easypermissions.EasyPermissions

class PermissionUtilAct : Activity() , EasyPermissions.PermissionCallbacks{

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        callbackArray[requestCode]?.onPermissionsDenied(requestCode, perms)
        finish()
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        callbackArray[requestCode]?.onPermissionsGranted(requestCode, perms)
        finish()
    }

    companion object {
        private var PERMISSION_ID = 123
        const val KEY_PERMISSIONS = "KEY_PERMISSIONS"

        val callbackArray: SparseArray<EasyPermissions.PermissionCallbacks> = SparseArray()

        fun request(context: Context, callback: EasyPermissions.PermissionCallbacks, vararg permissions: String){
            PERMISSION_ID += 1

            callbackArray.append(PERMISSION_ID, callback)

            val intent = Intent(context, PermissionUtilAct::class.java)
            intent.putExtra(KEY_PERMISSIONS, permissions)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)

        EasyPermissions.requestPermissions(this, "使用语音识别功能需要授予权限", PERMISSION_ID,*intent?.getStringArrayExtra(KEY_PERMISSIONS)!!)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onDestroy() {
        super.onDestroy()

        callbackArray.clear()
    }

}
