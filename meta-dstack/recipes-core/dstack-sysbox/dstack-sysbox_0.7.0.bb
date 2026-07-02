SUMMARY = "Sysbox container runtime"
DESCRIPTION = "An open-source, next-generation runc that empowers rootless containers \
to run workloads such as Systemd, Docker, Kubernetes, just like VMs."
HOMEPAGE = "https://github.com/nestybox/sysbox"
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=cf0915b5e4f1337cf5b929ba1e388c42"

SYSBOX_VERSION = "0.7.0"

# Pin all submodule revisions from the v0.7.0 tag for reproducibility.
SRCREV_sysbox = "27cb713f973b0a8de744519ce5d19d69f0280844"
SRCREV_sysbox-runc = "a4dd414f7b9b7455c0fbf0d5e5db7bcfe30645bc"
SRCREV_sysbox-fs = "b70bd38bbf72bf0e759c5f7d8c38925d717068ae"
SRCREV_sysbox-mgr = "bff3721f86e737cfa129dfe9fe2e7933692aba26"
SRCREV_sysbox-ipc = "f05151f4b4c1df63d7fd241577ca032905c1bd0e"
SRCREV_sysbox-libs = "9b42cd43fad17198c93ff660d9bb269e7c74366d"
# bazil/fuse for sysbox-fs; pinned to nestybox/fuse master (the submodule's upstream).
SRCREV_sysbox-fuse = "5ce319439091b08f33b149cb2482039f1b0a24fe"

SRCREV_FORMAT = "sysbox"

SRC_URI = " \
    git://github.com/nestybox/sysbox.git;nobranch=1;name=sysbox;protocol=https;destsuffix=sysbox \
    git://github.com/nestybox/sysbox-runc.git;nobranch=1;name=sysbox-runc;protocol=https;destsuffix=sysbox-runc \
    git://github.com/nestybox/sysbox-fs.git;nobranch=1;name=sysbox-fs;protocol=https;destsuffix=sysbox-fs \
    git://github.com/nestybox/sysbox-mgr.git;nobranch=1;name=sysbox-mgr;protocol=https;destsuffix=sysbox-mgr \
    git://github.com/nestybox/sysbox-ipc.git;nobranch=1;name=sysbox-ipc;protocol=https;destsuffix=sysbox-ipc \
    git://github.com/nestybox/sysbox-libs.git;nobranch=1;name=sysbox-libs;protocol=https;destsuffix=sysbox-libs \
    git://github.com/nestybox/fuse.git;branch=master;name=sysbox-fuse;protocol=https;destsuffix=sysbox-fuse \
    file://sysbox.service \
    file://sysbox-fs.service \
    file://sysbox-mgr.service \
    file://99-sysbox-sysctl.conf \
    file://50-sysbox-mod.conf \
    file://sysboxFsProtobuf.pb.go \
    file://sysboxMgrProtobuf.pb.go \
"

S = "${WORKDIR}/sysbox"

PV = "${SYSBOX_VERSION}+git${SRCPV}"

DEPENDS += "libseccomp"
RDEPENDS:${PN} += "libseccomp rsync fuse"

inherit go goarch pkgconfig systemd

GO_IMPORT = "github.com/nestybox/sysbox"

SYSBOX_LDFLAGS = " \
    -X 'main.edition=Community Edition (CE)' \
    -X main.version=${SYSBOX_VERSION} \
    -X main.commitId=${SRCREV_sysbox} \
    -X 'main.builtAt=1970-01-01T00:00:00Z' \
    -X 'main.builtBy=dstack' \
"

# Kernel >= 5.12 supports idmapped mounts
SYSBOX_RUNC_BUILDTAGS = "seccomp idmapped_mnt"
SYSBOX_MGR_BUILDTAGS = "idmapped_mnt"

do_configure() {
    # Arrange the source tree so that go.mod replace directives work.
    # All components expect to find siblings in ../ relative to themselves.
    # The git fetcher places them in ${WORKDIR}/sysbox-{runc,fs,mgr,ipc,libs}.
    # This is already the correct layout since they are all at the same level
    # under ${WORKDIR}.

    # sysbox-fs expects a 'bazil' subdirectory (submodule of nestybox/fuse).
    # Remove the empty submodule placeholder left by git checkout, then symlink.
    rm -rf ${WORKDIR}/sysbox-fs/bazil
    ln -sfn ${WORKDIR}/sysbox-fuse ${WORKDIR}/sysbox-fs/bazil

    # Install pre-generated protobuf Go files. The upstream repo only ships
    # .proto files and expects protoc + protoc-gen-go at build time. We
    # pre-generate them to avoid the protoc native toolchain dependency.
    # sysbox-ipc is unchanged from 0.6.7, so the vendored files still match.
    install -m 0644 ${WORKDIR}/sysboxFsProtobuf.pb.go \
        ${WORKDIR}/sysbox-ipc/sysboxFsGrpc/sysboxFsProtobuf/
    install -m 0644 ${WORKDIR}/sysboxMgrProtobuf.pb.go \
        ${WORKDIR}/sysbox-ipc/sysboxMgrGrpc/sysboxMgrProtobuf/

    # Vendor dependencies for each component so that do_compile needs no
    # network access. go.sum in each repo guarantees content integrity.
    # Use -modcacherw so cached modules are writable (BitBake needs to
    # clean ${B}/pkg/mod between tasks).
    for mod in sysbox-runc sysbox-fs sysbox-mgr; do
        cd ${WORKDIR}/$mod
        ${GO} mod vendor -modcacherw
    done
}

do_configure[network] = "1"

do_compile() {
    export CGO_ENABLED="1"
    export CGO_CFLAGS="${CFLAGS} --sysroot=${STAGING_DIR_TARGET}"
    export CGO_LDFLAGS="${LDFLAGS} -Wl,--build-id=none --sysroot=${STAGING_DIR_TARGET}"
    export CFLAGS=""
    export LDFLAGS=""

    # Set reproducible build environment
    export SOURCE_DATE_EPOCH=0
    export TZ=UTC

    # Build sysbox-runc
    cd ${WORKDIR}/sysbox-runc
    ${GO} build -mod=vendor -buildvcs=false -trimpath \
        -tags "${SYSBOX_RUNC_BUILDTAGS}" \
        -ldflags "-buildid= -s -w -linkmode external -extldflags '-Wl,--build-id=none' ${SYSBOX_LDFLAGS}" \
        -o ${WORKDIR}/sysbox-runc-bin .

    # Build sysbox-fs
    cd ${WORKDIR}/sysbox-fs
    ${GO} build -mod=vendor -buildvcs=false -trimpath \
        -ldflags "-buildid= -s -w -linkmode external -extldflags '-Wl,--build-id=none' ${SYSBOX_LDFLAGS}" \
        -o ${WORKDIR}/sysbox-fs-bin ./cmd/sysbox-fs

    # Build sysbox-mgr
    cd ${WORKDIR}/sysbox-mgr
    ${GO} build -mod=vendor -buildvcs=false -trimpath \
        -tags "${SYSBOX_MGR_BUILDTAGS}" \
        -ldflags "-buildid= -s -w -linkmode external -extldflags '-Wl,--build-id=none' ${SYSBOX_LDFLAGS}" \
        -o ${WORKDIR}/sysbox-mgr-bin .
}

do_install() {
    # Install binaries
    install -d ${D}${bindir}
    install -m 0755 ${WORKDIR}/sysbox-runc-bin ${D}${bindir}/sysbox-runc
    install -m 0755 ${WORKDIR}/sysbox-fs-bin ${D}${bindir}/sysbox-fs
    install -m 0755 ${WORKDIR}/sysbox-mgr-bin ${D}${bindir}/sysbox-mgr

    # Install systemd services
    if ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'true', 'false', d)}; then
        install -d ${D}${systemd_system_unitdir}
        install -m 0644 ${WORKDIR}/sysbox.service ${D}${systemd_system_unitdir}
        install -m 0644 ${WORKDIR}/sysbox-fs.service ${D}${systemd_system_unitdir}
        install -m 0644 ${WORKDIR}/sysbox-mgr.service ${D}${systemd_system_unitdir}
    fi

    # Install sysctl config
    install -d ${D}${sysconfdir}/sysctl.d
    install -m 0644 ${WORKDIR}/99-sysbox-sysctl.conf ${D}${sysconfdir}/sysctl.d/

    # Install module autoload config
    install -d ${D}${sysconfdir}/modules-load.d
    install -m 0644 ${WORKDIR}/50-sysbox-mod.conf ${D}${sysconfdir}/modules-load.d/

    # Create sysbox data directory
    install -d ${D}/var/lib/sysbox
}

SYSTEMD_PACKAGES = "${@bb.utils.contains('DISTRO_FEATURES', 'systemd', '${PN}', '', d)}"
SYSTEMD_SERVICE:${PN} = "sysbox.service sysbox-fs.service sysbox-mgr.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

FILES:${PN} += " \
    ${bindir}/sysbox-runc \
    ${bindir}/sysbox-fs \
    ${bindir}/sysbox-mgr \
    ${systemd_system_unitdir}/sysbox.service \
    ${systemd_system_unitdir}/sysbox-fs.service \
    ${systemd_system_unitdir}/sysbox-mgr.service \
    ${sysconfdir}/sysctl.d/99-sysbox-sysctl.conf \
    ${sysconfdir}/modules-load.d/50-sysbox-mod.conf \
    /var/lib/sysbox \
"

# Pre-create subuid/subgid entries for sysbox user namespace mappings.
# sysbox-mgr tries to write these at startup, but rootfs is read-only (dm-verity).
# If the correct entry already exists, sysbox-mgr skips the write.
# This runs at rootfs creation time (not first boot).
pkg_postinst:${PN}() {
    echo "sysbox:100000:65536" >> $D${sysconfdir}/subuid
    echo "sysbox:100000:65536" >> $D${sysconfdir}/subgid
}

INSANE_SKIP:${PN} += "ldflags already-stripped"

COMPATIBLE_HOST = "x86_64.*-linux"
