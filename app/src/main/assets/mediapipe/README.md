# MediaPipe 人脸模型（随包）

| 文件 | 来源 | SHA-256 | 许可 |
|---|---|---|---|
| `face_landmarker.task` | https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | Apache-2.0 |
| `blaze_face_short_range.tflite` | https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` | Apache-2.0 |

调用方：`playback/face/MediaPipeFaceRegionDetector.kt`。任一文件缺失时只降级（网格 → 检测框 → 整帧），不会让自动调色失败。
