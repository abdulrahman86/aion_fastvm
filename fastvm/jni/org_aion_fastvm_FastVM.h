/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class org_aion_fastvm_FastVM */

#ifndef _Included_org_aion_fastvm_FastVM
#define _Included_org_aion_fastvm_FastVM
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     org_aion_fastvm_FastVM
 * Method:    init
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_aion_fastvm_FastVM_init
  (JNIEnv *, jclass);

/*
 * Class:     org_aion_fastvm_FastVM
 * Method:    create
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_org_aion_fastvm_FastVM_create
  (JNIEnv *, jclass);

/*
 * Class:     org_aion_fastvm_FastVM
 * Method:    run
 * Signature: (J[B[BI)[B
 */
JNIEXPORT jbyteArray JNICALL Java_org_aion_fastvm_FastVM_run
  (JNIEnv *, jclass, jlong, jbyteArray, jbyteArray, jint);

/*
 * Class:     org_aion_fastvm_FastVM
 * Method:    destroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_org_aion_fastvm_FastVM_destroy
  (JNIEnv *, jclass, jlong);

#ifdef __cplusplus
}
#endif
#endif