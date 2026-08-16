package com.example.matrizapp
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes

object GoogleSheetsServiceProvider {
    private val HTTP_TRANSPORT = NetHttpTransport()
    private val JSON_FACTORY = GsonFactory.getDefaultInstance()

    fun getService(context: Context): Sheets {
        val credential = getCredential(context, listOf(SheetsScopes.SPREADSHEETS, DriveScopes.DRIVE))
        return Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName("Matriz App").build()
    }

    fun getDriveService(context: Context): Drive {
        val credential = getCredential(context, listOf(SheetsScopes.SPREADSHEETS, DriveScopes.DRIVE))
        return Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName("Matriz App").build()
    }

    private fun getCredential(context: Context, scopes: List<String>): GoogleAccountCredential {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        val credential = GoogleAccountCredential.usingOAuth2(context, scopes)
        if (lastAccount != null) { credential.selectedAccount = lastAccount.account }
        return credential
    }
}