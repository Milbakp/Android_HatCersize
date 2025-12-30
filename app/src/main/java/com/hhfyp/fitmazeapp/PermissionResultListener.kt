package com.hhfyp.fitmazeapp

interface PermissionResultListener {
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
}