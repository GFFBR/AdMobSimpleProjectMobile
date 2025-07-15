#include <jni.h>
#include <string>
#include <thread>
#include <atomic>
#include <chrono>
#include <unistd.h>
#include <sys/stat.h>
#include <fstream>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/ptrace.h>
#include <vector>

#define HIDDEN __attribute__((visibility("hidden")))
// #define LOG_TAG "UnlockerSecurity"
// #define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// --- Variáveis Globais de Segurança ---
JavaVM* g_vm = nullptr;
jobject g_main_activity_obj = nullptr;
std::atomic<bool> g_is_protector_running(false);
std::atomic<long long> g_java_heartbeat;
std::atomic<bool> g_ui_is_ready(false); // <<< VARIÁVEL DE SINALIZAÇÃO

// --- Protótipos das Funções de Segurança ---
HIDDEN bool is_debugger_attached();
HIDDEN bool has_root_access();
HIDDEN bool is_running_on_emulator();
HIDDEN bool has_suspicious_libraries();
HIDDEN bool are_developer_options_enabled(JNIEnv* env);
HIDDEN bool check_app_signature(JNIEnv* env);
HIDDEN void security_monitor_thread();

// --- Implementação JNI ---

extern "C" JNIEXPORT jstring JNICALL
Java_com_qnetwing_unlock_MainActivity_stringFromJNI(JNIEnv* env, jobject) {
    std::string hello = "Unlocker Security Initialized";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_qnetwing_unlock_MainActivity_startSecurityProtector(JNIEnv* env, jobject thiz) {
    if (g_main_activity_obj == nullptr) {
        env->GetJavaVM(&g_vm);
        g_main_activity_obj = env->NewGlobalRef(thiz);
        g_is_protector_running = true;
        // Inicia a thread, que ficará em espera.
        std::thread(security_monitor_thread).detach();
    }
}

// <<< NOVA FUNÇÃO PARA RECEBER O SINAL DA UI >>>
extern "C" JNIEXPORT void JNICALL
Java_com_qnetwing_unlock_MainActivity_signalUiReady(JNIEnv* env, jobject) {
    g_ui_is_ready = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qnetwing_unlock_MainActivity_stopSecurityProtector(JNIEnv* env, jobject) {
    g_is_protector_running = false;
    if (g_main_activity_obj != nullptr) {
        env->DeleteGlobalRef(g_main_activity_obj);
        g_main_activity_obj = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_qnetwing_unlock_MainActivity_updateJavaHeartbeat(JNIEnv* env, jobject) {
    g_java_heartbeat = std::chrono::steady_clock::now().time_since_epoch().count();
}

void security_monitor_thread() {
    JNIEnv* env;
    g_vm->AttachCurrentThread(&env, nullptr);
    
    // <<< ESPERA ATIVA PELO SINAL DA UI >>>
    while (!g_ui_is_ready && g_is_protector_running) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    
    // Se o app fechou antes da UI ficar pronta, termina a thread.
    if (!g_is_protector_running) {
        g_vm->DetachCurrentThread();
        return;
    }

    jclass activityClass = env->GetObjectClass(g_main_activity_obj);
    jmethodID terminateMethodID = env->GetMethodID(activityClass, "terminateApp", "()V");

    if (terminateMethodID == nullptr) {
        g_vm->DetachCurrentThread();
        return;
    }

    g_java_heartbeat = std::chrono::steady_clock::now().time_since_epoch().count();

    while (g_is_protector_running) {
        long long last_java_beat = g_java_heartbeat.load();
        long long current_time_ns = std::chrono::steady_clock::now().time_since_epoch().count();

        if ((current_time_ns - last_java_beat) > 10000000000LL) {
             env->CallVoidMethod(g_main_activity_obj, terminateMethodID);
             break;
        }

        if (is_debugger_attached() || has_root_access() || is_running_on_emulator() ||
            has_suspicious_libraries() || are_developer_options_enabled(env) || !check_app_signature(env))
        {
            env->CallVoidMethod(g_main_activity_obj, terminateMethodID);
            break;
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
    g_vm->DetachCurrentThread();
}

// --- Implementações das Funções de Segurança (sem alterações) ---

HIDDEN bool is_debugger_attached() {
    try {
        std::ifstream status("/proc/self/status");
        std::string line;
        while (std::getline(status, line)) {
            if (line.rfind("TracerPid:", 0) == 0) {
                if (std::stoi(line.substr(10)) != 0) return true;
                break;
            }
        }
    } catch (...) {}
    return false;
}

HIDDEN bool has_root_access() {
    const char* su_paths[] = {
        "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su", "/magisk/.core/bin/su"
    };
    for (const char* path : su_paths) {
        if (access(path, F_OK) == 0) return true;
    }
    return false;
}

HIDDEN bool is_running_on_emulator() {
     std::string ro_hardware;
     std::ifstream hardware_file("/proc/cpuinfo");
     if (hardware_file.is_open()) {
         std::string line;
         while(std::getline(hardware_file, line)){
             if (line.rfind("Hardware", 0) == 0){
                 ro_hardware = line.substr(line.find(":") + 2);
                 break;
             }
         }
     }
     return ro_hardware.find("goldfish") != std::string::npos ||
            ro_hardware.find("ranchu") != std::string::npos ||
            ro_hardware.find("vbox86") != std::string::npos;
}

HIDDEN bool has_suspicious_libraries() {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    const char* suspicious[] = {"substrate", "xposed", "frida", "gameguardian", "libgg", "magisk"};
    while (std::getline(maps, line)) {
        for (const char* lib : suspicious) {
            if (line.find(lib) != std::string::npos) {
                maps.close();
                return true;
            }
        }
    }
    maps.close();
    return false;
}

HIDDEN bool are_developer_options_enabled(JNIEnv* env) {
    if (g_main_activity_obj == nullptr) return false;
    jclass activityClass = env->GetObjectClass(g_main_activity_obj);
    if (activityClass == nullptr) return false;
    jmethodID methodId = env->GetMethodID(activityClass, "areDeveloperOptionsEnabled", "()Z");
    if (methodId == nullptr) return false;
    return env->CallBooleanMethod(g_main_activity_obj, methodId) == JNI_TRUE;
}

HIDDEN bool check_app_signature(JNIEnv* env) {
    const int CORRECT_SIGNATURE_HASH = -1062479167; // Substitua pelo seu hash de produção

    if (g_main_activity_obj == nullptr) return false;
    jclass activityClass = env->GetObjectClass(g_main_activity_obj);
    jmethodID getSignatureHashMethodID = env->GetMethodID(activityClass, "getSignatureHash", "()I");
    if (getSignatureHashMethodID == nullptr) return false;
    jint currentHash = env->CallIntMethod(g_main_activity_obj, getSignatureHashMethodID);
    return currentHash == CORRECT_SIGNATURE_HASH;
}
