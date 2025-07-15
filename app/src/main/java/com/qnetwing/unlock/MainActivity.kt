package com.qnetwing.unlock

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.qnetwing.unlock.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mInterstitialAd: InterstitialAd? = null

    private val pageLoadsPerAd = 4
    private var pageLoadCounter = 0
    private var adWasShown = false

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            updateJavaHeartbeat()
            heartbeatHandler.postDelayed(this, 2000)
        }
    }
    
    // <<< NOVA VARIÁVEL DE CONTROLO >>>
    private var isInitialPageLoad = true

    // --- Funções Nativas de Segurança ---
    private external fun stringFromJNI(): String
    private external fun startSecurityProtector()
    private external fun stopSecurityProtector()
    private external fun updateJavaHeartbeat()
    // <<< NOVA FUNÇÃO DE SINALIZAÇÃO >>>
    private external fun signalUiReady() 

    init {
        System.loadLibrary("unlock-jni")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apenas prepara a segurança, sem ativar nada ainda.
        startSecurityProtector()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val messageFromJNI = stringFromJNI()
        Toast.makeText(this, messageFromJNI, Toast.LENGTH_LONG).show()

        MobileAds.initialize(this) {}
        loadBannerAds()
        loadInterstitialAd()
        setupWebView() // A lógica de sinalização está agora dentro deste método

        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }
    }

    override fun onResume() {
        super.onResume()
        heartbeatHandler.post(heartbeatRunnable)
        
        if (adWasShown) {
            adWasShown = false
            sendUnlockBroadcast()
            startTargetApp()
        }
    }

    override fun onPause() {
        super.onPause()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSecurityProtector()
    }
    
    @Suppress("DEPRECATION")
    fun getSignatureHash(): Int {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo.apkContentsSigners
            } else {
                packageInfo.signatures
            }
            if (signatures.isNotEmpty()) {
                return signatures[0].hashCode()
            }
        } catch (e: Exception) { /* Ignorar em produção */ }
        return 0
    }
    
    fun areDeveloperOptionsEnabled(): Boolean {
        return Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    }

    fun terminateApp() {
        runOnUiThread {
            finish()
            System.exit(0)
        }
    }

    private fun loadBannerAds() {
        val adRequest = AdRequest.Builder().build()
        binding.adViewHeader.loadAd(adRequest)
        binding.adViewFooter.loadAd(adRequest)
    }

    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        val adUnitId = getString(R.string.admob_interstitial_id)
        InterstitialAd.load(this, adUnitId, adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                mInterstitialAd = interstitialAd
                setupInterstitialCallback()
            }
            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                mInterstitialAd = null
            }
        })
    }

    private fun setupInterstitialCallback() {
        mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitialAd()
            }
        }
    }

    private fun showInterstitialAd() {
        if (mInterstitialAd != null) {
            adWasShown = true
            mInterstitialAd?.show(this)
        } else {
            loadInterstitialAd()
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                binding.swipeRefreshLayout.isEnabled = (scrollY == 0)
            }
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.progressBar.visibility = View.VISIBLE
                    pageLoadCounter++
                    if (pageLoadCounter % pageLoadsPerAd == 0) {
                        showInterstitialAd()
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE // Esconde o progresso
                    binding.swipeRefreshLayout.isRefreshing = false
                    
                    // <<< LÓGICA DE SINALIZAÇÃO AQUI >>>
                    if (isInitialPageLoad) {
                        isInitialPageLoad = false
                        // Envia o sinal para o C++ de que a UI está pronta e visível
                        signalUiReady() 
                    }
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    view?.loadUrl("file:///android_asset/error.html")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progressBar.progress = newProgress
                }
            }
            loadUrl("https://gffbr.github.io/website/")
        }
    }
    
    private fun sendUnlockBroadcast() {
        CoroutineScope(Dispatchers.IO).launch {
            val adId = try { AdvertisingIdClient.getAdvertisingIdInfo(applicationContext).id } catch (e: Exception) { "unavailable" }
            val intent = Intent("com.qnetwing.unlock.AD_ASSISTIDO").apply {
                setPackage("com.qnet.wing")
                putExtra("ad_watched_token", true)
                putExtra("secret_key", "Ch4v3_S3cr3t4_QNetW1ng_@2025!")
                putExtra("broadcast_timestamp", System.currentTimeMillis())
                putExtra("broadcast_nonce", adId)
            }
            sendBroadcast(intent)
        }
    }

    private fun startTargetApp() {
        val targetPackage = "com.qnet.wing"
        val targetActivity = "com.qnet.wing.MainActivity"
        try {
            val intent = Intent().apply {
                component = ComponentName(targetPackage, targetActivity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val genericIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (genericIntent != null) {
                startActivity(genericIntent)
            }
        }
    }
}
