package br.com.smbstore.ntfy.app

import android.app.Application
import br.com.smbstore.ntfy.db.Repository
import br.com.smbstore.ntfy.util.Log

class Application : Application() {
    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }
}
