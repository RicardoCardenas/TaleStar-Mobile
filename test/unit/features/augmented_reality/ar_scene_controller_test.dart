import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:startale_mobile/core/network/dio_client.dart';
import 'package:startale_mobile/core/storage/secure_storage_service.dart';
import 'package:startale_mobile/features/augmented_reality/data/services/ar_scene_controller.dart';
import '../../../fixtures/ar_reader_fixtures.dart';

class MockSecureStorage extends Mock implements SecureStorageService {}

class ImageAdapter implements HttpClientAdapter {
  final Future<ResponseBody> Function(RequestOptions) respond;
  ImageAdapter(this.respond);
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<dynamic>? cancelFuture,
  ) => respond(options);
  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Dio dio;
  late List<MethodCall> uploads;

  setUp(() {
    final storage = MockSecureStorage();
    when(
      () => storage.getToken(),
    ).thenAnswer((_) async => 'test-session-token');
    dio = DioClient(
      secureStorageService: storage,
      customBaseUrl: 'https://backend.example',
    ).dio;
    uploads = [];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(ArSceneController.channel, (call) async {
          uploads.add(call);
          return true;
        });
  });

  tearDown(() {
    dio.close(force: true);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(ArSceneController.channel, null);
  });

  test(
    'downloads the linked asset with the session and sends bytes to native AR',
    () async {
      dio.httpClientAdapter = ImageAdapter((request) async {
        expect(
          request.uri.toString(),
          'https://backend.example/api/v1/media/assets/image-1',
        );
        expect(request.headers['Authorization'], 'Bearer test-session-token');
        expect(request.headers['Accept'], 'image/*');
        return ResponseBody.fromBytes(
          [1, 2, 3],
          200,
          headers: {
            'content-type': ['image/png'],
          },
        );
      });
      await ArSceneController(dio: dio).setPage(arTestPages.first, 1, 2);
      final args = uploads.single.arguments as Map;
      expect(args['imageBytes'], [1, 2, 3]);
      expect(args['text'], arTestPages.first.text);
      expect(args.containsKey('asset'), isFalse);
    },
  );

  test(
    'an older slow download cannot replace the latest selected page',
    () async {
      final firstDownload = Completer<ResponseBody>();
      final started = Completer<void>();
      dio.httpClientAdapter = ImageAdapter((request) {
        if (request.path.endsWith('image-1')) {
          started.complete();
          return firstDownload.future;
        }
        return Future.value(ResponseBody.fromBytes([2], 200));
      });
      final controller = ArSceneController(dio: dio);
      final oldRequest = controller.setPage(arTestPages.first, 1, 2);
      await started.future;
      await controller.setPage(arTestPages.last, 2, 2);
      firstDownload.complete(ResponseBody.fromBytes([1], 200));
      await oldRequest;
      expect(uploads.length, 1);
      expect((uploads.single.arguments as Map)['page'], 2);
    },
  );

  test(
    'failed media replaces stale page content and reports the backend error',
    () async {
      dio.httpClientAdapter = ImageAdapter(
        (_) async => ResponseBody.fromString('Not found', 404),
      );
      await expectLater(
        ArSceneController(dio: dio).setPage(arTestPages.first, 1, 2),
        throwsA(isA<DioException>()),
      );
      final args = uploads.single.arguments as Map;
      expect(args['imageBytes'], isNull);
      expect(args['text'], arTestPages.first.text);
    },
  );
}
