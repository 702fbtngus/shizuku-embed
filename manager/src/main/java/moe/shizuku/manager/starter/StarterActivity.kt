package moe.shizuku.manager.starter

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.viewModelScope
import moe.shizuku.manager.recovery.BackendRecovery
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants.EXTRA
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.StarterActivityBinding
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import java.net.ConnectException
import javax.net.ssl.SSLProtocolException

class StarterActivity : AppBarActivity() {

    private val viewModel by viewModels {
        ViewModel(
            if (intent.getBooleanExtra(EXTRA_RECOVERY, false))
                ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
            else intent.getBooleanExtra(EXTRA_IS_ROOT, true),
            intent.getStringExtra(EXTRA_HOST),
            intent.getIntExtra(EXTRA_PORT, 0)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close_24)

        val binding = StarterActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.started.observe(this) { started ->
            if (started) window.decorView.postDelayed({ if (!isFinishing) finish() }, 3000)
        }
        viewModel.output.observe(this) {
            val output = it.data!!.trim()
            if (it.status == Status.ERROR) {
                var message = 0
                when (it.error) {
                    is AdbKeyException -> {
                        message = R.string.adb_error_key_store
                    }
                    is ConnectException -> {
                        message = R.string.cannot_connect_port
                    }
                    is SSLProtocolException -> {
                        message = R.string.adb_pair_required
                    }
                }

                if (message != 0) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            binding.text1.text = output
        }
    }

    companion object {

        const val EXTRA_RECOVERY = "$EXTRA.RECOVERY"
        const val EXTRA_IS_ROOT = "$EXTRA.IS_ROOT"
        const val EXTRA_HOST = "$EXTRA.HOST"
        const val EXTRA_PORT = "$EXTRA.PORT"
    }
}

private class ViewModel(root: Boolean, host: String?, port: Int) : androidx.lifecycle.ViewModel() {
    private val sb = StringBuilder()
    private val _output = MutableLiveData<Resource<String>>()
    val output: LiveData<Resource<String>> = _output
    val started = MutableLiveData(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BackendRecovery.startManual(root, host, port) { line ->
                    sb.appendLine(line)
                    _output.postValue(Resource.success(sb.toString()))
                }
                started.postValue(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sb.appendLine(Log.getStackTraceString(e))
                _output.postValue(Resource.error(e, sb.toString()))
            }
        }
    }
}
