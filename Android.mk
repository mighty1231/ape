# Copyright 2008 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_DEX_PREOPT := false
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := ape
include $(BUILD_JAVA_LIBRARY)
