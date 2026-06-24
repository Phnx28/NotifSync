---
title: "Cache EncryptedSharedPreferences instance"
labels: enhancement, performance
---

**Current behavior**

`encryptedPrefs()` in `ReceiverForegroundService` calls `MasterKey.Builder()` on every access. While `MasterKey.Builder` caches internally via AndroidKeyStore, the `SharedPreferences` object itself is re-created each time.

**Improvement**

Cache the `SharedPreferences` instance in a property:

```kotlin
private val prefs by lazy {
    EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
```

Then replace `encryptedPrefs()` with just `prefs`.

**See also**
- https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences