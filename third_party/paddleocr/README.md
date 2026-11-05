# PaddleOCR Android SDK and PP-OCRv5 models

The source under `app/src/main/java/com/paddle/ocr` is copied from PaddleOCR's
`deploy/ppocr-android/ppocr-sdk/src/main/java` at commit
`dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`. Its original copyright headers and
[Apache-2.0 license](LICENSE) are retained.

The bundled models are the official PaddlePaddle ONNX exports:

* [PP-OCRv5_mobile_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx), revision `e6f4fa85f00e168c862bc462aebca69eef9b3d3d`, SHA-256 `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d`.
* [PP-OCRv5_mobile_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx), revision `ed152b8b495f84de93cda5709d768548a9127622`, SHA-256 `da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092`; its accompanying character dictionary has SHA-256 `5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e`.

Both model cards specify Apache-2.0. The application uses ONNX Runtime and the
official OpenCV Android package; their licenses are provided by those projects.
