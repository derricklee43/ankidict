package com.derricklee.ankidict

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityMainBinding

private const val PERMISSION_REQUEST_CODE = 1
private const val SEARCH_DEBOUNCE_MS = 250L
private const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AnkiRepository
    private lateinit var searchService: SearchService
    private lateinit var adapter: SearchResultAdapter

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AnkiRepository(this)
        searchService = SearchService(repository, DictionaryRepository(this))
        adapter = SearchResultAdapter(AudioRepository(this))

        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty())
            }
        })

        checkAccessAndMaybeSearch()
    }

    override fun onDestroy() {
        adapter.stopAudio()
        super.onDestroy()
    }

    private fun scheduleSearch(query: String) {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val runnable = Runnable { runSearch(query) }
        pendingSearch = runnable
        searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun checkAccessAndMaybeSearch() {
        if (!repository.isAnkiDroidAvailable()) {
            showStatus(getString(R.string.status_ankidroid_missing))
            return
        }
        if (!hasReadWritePermission()) {
            showStatus(getString(R.string.status_permission_needed))
            requestPermissions(arrayOf(READ_WRITE_PERMISSION), PERMISSION_REQUEST_CODE)
            return
        }
        hideStatus()
    }

    private fun hasReadWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(this, READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                hideStatus()
                scheduleSearch(binding.searchBox.text?.toString().orEmpty())
            } else {
                showStatus(getString(R.string.status_permission_denied))
            }
        }
    }

    private fun runSearch(query: String) {
        if (!repository.isAnkiDroidAvailable() || !hasReadWritePermission()) return
        if (query.isBlank()) {
            adapter.submitList(emptyList())
            return
        }
        adapter.submitList(searchService.search(query))
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = android.view.View.VISIBLE
    }

    private fun hideStatus() {
        binding.statusText.visibility = android.view.View.GONE
    }
}
