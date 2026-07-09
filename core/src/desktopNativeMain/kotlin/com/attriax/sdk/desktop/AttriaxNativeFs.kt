package com.attriax.sdk.desktop

/**
 * Create the directory [path] (and it is a no-op if it already exists). The POSIX
 * `mkdir` signature diverges between the built native targets — MinGW declares
 * `mkdir(const char*)` (mode-less) while glibc declares `mkdir(const char*, mode_t)`
 * — so the actual lives in each target source set (mingwX64Main / linuxX64Main).
 * Best-effort: a failure (e.g. already-exists) is ignored; the subsequent file
 * write surfaces any real, fatal path problem.
 */
internal expect fun attriaxEnsureDir(path: String)
