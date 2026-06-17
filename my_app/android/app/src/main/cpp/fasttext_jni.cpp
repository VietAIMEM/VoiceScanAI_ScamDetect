#include <jni.h>

#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "fasttext.h"

namespace {

std::unique_ptr<fasttext::FastText> g_model;

jstring toJString(JNIEnv* env, const std::string& value) {
  return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_my_1app_ml_FastTextNative_loadModel(
    JNIEnv* env,
    jobject,
    jstring modelPath) {
  const char* path = env->GetStringUTFChars(modelPath, nullptr);
  try {
    auto model = std::make_unique<fasttext::FastText>();
    model->loadModel(std::string(path));
    g_model = std::move(model);
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_TRUE;
  } catch (const std::exception& error) {
    env->ReleaseStringUTFChars(modelPath, path);
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exceptionClass, error.what());
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_my_1app_ml_FastTextNative_predict(
    JNIEnv* env,
    jobject,
    jstring text,
    jint k,
    jfloat threshold) {
  if (!g_model) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(exceptionClass, "fastText model is not loaded");
    return nullptr;
  }

  const char* rawText = env->GetStringUTFChars(text, nullptr);
  std::istringstream input{std::string(rawText)};
  env->ReleaseStringUTFChars(text, rawText);

  std::vector<std::pair<fasttext::real, std::string>> predictions;
  g_model->predictLine(input, predictions, k, threshold);

  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray output =
      env->NewObjectArray(static_cast<jsize>(predictions.size() * 2), stringClass, nullptr);

  for (size_t index = 0; index < predictions.size(); ++index) {
    env->SetObjectArrayElement(
        output,
        static_cast<jsize>(index * 2),
        toJString(env, predictions[index].second));
    env->SetObjectArrayElement(
        output,
        static_cast<jsize>(index * 2 + 1),
        toJString(env, std::to_string(predictions[index].first)));
  }

  return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_my_1app_ml_FastTextNative_close(
    JNIEnv*,
    jobject) {
  g_model.reset();
}
