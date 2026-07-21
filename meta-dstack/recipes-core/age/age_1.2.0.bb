SUMMARY = "age — simple, modern file encryption tool (prebuilt static binary)"
HOMEPAGE = "https://github.com/FiloSottile/age"
LICENSE = "BSD-3-Clause"
LIC_FILES_CHKSUM = "file://LICENSE;md5=f9f6c459333e830a9e244d07b90042e9"

PV = "1.2.0"

# Baked into the read-only guest rootfs so the orchestrator finds age at the path it
# expects (/usr/local/bin/age); non-TEE nodes still download this same release at runtime.
SRC_URI = "https://github.com/FiloSottile/age/releases/download/v${PV}/age-v${PV}-linux-amd64.tar.gz"
SRC_URI[sha256sum] = "2ae71cb3ea761118937a944083f057cfd42f0ef11d197ce72fc2b8780d50c4ef"

S = "${WORKDIR}/age"

COMPATIBLE_HOST = "x86_64.*-linux"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

# Prebuilt static Go binary: install as-is; skip the from-source QA checks.
INHIBIT_PACKAGE_STRIP = "1"
INHIBIT_PACKAGE_DEBUG_SPLIT = "1"
INHIBIT_SYSROOT_STRIP = "1"
EXCLUDE_FROM_SHLIBS = "1"
INSANE_SKIP:${PN} += "already-stripped ldflags buildpaths"

do_install() {
    install -d ${D}/usr/local/bin
    install -m 0755 ${S}/age ${D}/usr/local/bin/age
}

FILES:${PN} = "/usr/local/bin/age"
