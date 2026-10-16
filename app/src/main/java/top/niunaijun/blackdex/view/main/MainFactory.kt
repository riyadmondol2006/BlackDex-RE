package top.niunaijun.blackdex.view.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackdex.data.DexDumpRepository

@Suppress("UNCHECKED_CAST")
class MainFactory(private val repo: DexDumpRepository) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repo) as T
    }
}
