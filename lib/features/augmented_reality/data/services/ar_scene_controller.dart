import 'package:flutter/services.dart';
import 'package:dio/dio.dart';
import '../../../../core/constants/api_constants.dart';
import '../../../../injection.dart';
import '../../../reader/domain/entities/story_page_detail.dart';

/// Commands affect the native world-space object, never a Flutter overlay.
class ArSceneController {
  static const channel = MethodChannel('com.startale.startale_mobile/ar_core');
  final Dio? _dio;
  int _pageRequest = 0;

  ArSceneController({Dio? dio}) : _dio = dio;

  Future<void> setPage(StoryPageDetail? page, int number, int total) async {
    final request = ++_pageRequest;
    Uint8List? imageBytes;
    Object? imageError;
    final assetId = page?.illustrationAssetId;
    if (assetId != null) {
      try {
        final response = await (_dio ?? sl<Dio>()).get<List<int>>(
          ApiConstants.mediaAsset(Uri.encodeComponent(assetId)),
          options: Options(
            responseType: ResponseType.bytes,
            headers: {'Accept': 'image/*'},
          ),
        );
        final bytes = response.data;
        if (bytes == null || bytes.isEmpty) {
          throw StateError('La ilustración está vacía.');
        }
        imageBytes = Uint8List.fromList(bytes);
      } catch (error) {
        imageError = error;
      }
    }
    // An earlier download must not replace the page the user just opened.
    if (request != _pageRequest) return;
    await channel
        .invokeMethod<bool>('setStoryPage', {
          'imageBytes': imageBytes,
          'text': page?.text ?? '',
          'page': number,
          'total': total,
        })
        .timeout(const Duration(seconds: 12));
    if (imageError != null) throw imageError;
  }

  Future<void> command(
    String command, [
    Map<String, Object> values = const {},
  ]) async {
    final success = await channel.invokeMethod<bool>('storySceneCommand', {
      'command': command,
      ...values,
    });
    if (success != true) throw StateError('La escena 3D no está disponible.');
  }
}
