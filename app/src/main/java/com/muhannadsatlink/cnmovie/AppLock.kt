package com.muhannadsatlink.cnmovie

// قفل التطبيق بالبصمة أو رمز الجهاز
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object AppLock {
    private fun auths() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    fun available(a: FragmentActivity) =
        BiometricManager.from(a).canAuthenticate(auths()) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(a: FragmentActivity, onOk: () -> Unit, onFail: () -> Unit) {
        val p = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(code: Int, msg: CharSequence) = onFail()
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("CN.MOVIE")
            .setSubtitle("افتح التطبيق بالبصمة أو رمز الجهاز")
            .setAllowedAuthenticators(auths())
            .apply { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) setNegativeButtonText("إلغاء") }
            .build()
        p.authenticate(info)
    }
}
