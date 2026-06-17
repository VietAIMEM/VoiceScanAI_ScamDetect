package com.example.my_app.ml

object FastTextNative {
    init {
        System.loadLibrary("scam_fasttext")
    }

    external fun loadModel(modelPath: String): Boolean
    external fun predict(text: String, k: Int, threshold: Float): Array<String>
    external fun close()
}
