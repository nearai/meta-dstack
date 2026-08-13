SUMMARY = "Persist the dstack guest system journal"
DESCRIPTION = "Mount the system journal on the encrypted dstack data volume before guest workloads start."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = " \
    file://dstack-persist-journal \
    file://dstack-persistent-journal.service \
    file://20-dstack-persistent-journal.conf \
    file://service-requires-persistent-journal.conf \
"

S = "${UNPACKDIR}"

inherit allarch systemd

RDEPENDS:${PN} += "bash systemd"

SYSTEMD_SERVICE:${PN} = "dstack-persistent-journal.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

JOURNAL_DEPENDENT_SERVICES = " \
    app-compose.service \
    containerd.service \
    docker.service \
    dstack-guest-agent.service \
    wg-checker.service \
"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/dstack-persist-journal ${D}${bindir}/dstack-persist-journal

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/dstack-persistent-journal.service \
        ${D}${systemd_system_unitdir}/dstack-persistent-journal.service

    install -d ${D}${sysconfdir}/systemd/journald.conf.d
    install -m 0644 ${S}/20-dstack-persistent-journal.conf \
        ${D}${sysconfdir}/systemd/journald.conf.d/20-dstack-persistent-journal.conf

    for service in ${JOURNAL_DEPENDENT_SERVICES}; do
        install -d ${D}${sysconfdir}/systemd/system/$service.d
        install -m 0644 ${S}/service-requires-persistent-journal.conf \
            ${D}${sysconfdir}/systemd/system/$service.d/20-dstack-persistent-journal.conf
    done
}

FILES:${PN} += "${sysconfdir}/systemd"
