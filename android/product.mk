# Include in device makefile to add OpenCyvis to system image
# Add to device/<vendor>/<device>/device.mk:
#   $(call inherit-product, path/to/opencyvis/android/product.mk)

LOCAL_PATH := $(call my-dir)

PRODUCT_PACKAGES += OpenCyvis

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/privapp-permissions-opencyvis.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-opencyvis.xml
