# Include from the target product makefile after the base product inherits common packages.
# Example:
#   $(call inherit-product, packages/apps/HomeLauncher/rom-integration/product/home_launcher_product.mk)

PRODUCT_PACKAGES += \
    HomeLauncher \
    privapp-permissions-com.home.launcher

# WARNING: Do not add HomeLauncherConfigOverlay here. It sets
# config_recentsComponentName = com.home.launcher, which causes
# SystemUI OverviewProxyService binding to fail (no QUICKSTEP_SERVICE
# implemented yet) and disables ALL gesture navigation.
# See rom-integration/docs/handover.md § Key Findings B.
# Re-enable only after implementing QUICKSTEP_SERVICE + IOverviewProxy.

# Optional final-ROM cleanup after HomeLauncher replaces Launcher3/QuickStep:
# PRODUCT_PACKAGES := $(filter-out Launcher3QuickStep,$(PRODUCT_PACKAGES))
