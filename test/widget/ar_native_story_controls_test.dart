import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';
import 'package:startale_mobile/features/augmented_reality/data/services/ar_scene_controller.dart';
import 'package:startale_mobile/features/augmented_reality/presentation/cubit/ar_cubit.dart';
import 'package:startale_mobile/features/augmented_reality/presentation/cubit/ar_state.dart';
import 'package:startale_mobile/features/augmented_reality/presentation/screens/ar_story_viewer_screen.dart';
import 'package:startale_mobile/features/augmented_reality/presentation/widgets/ar_story_card_3d.dart';
import '../fixtures/ar_reader_fixtures.dart';

class MockArCubit extends Mock implements ArCubit {}

class MockImageDio extends Mock implements Dio {}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late MockArCubit cubit;
  late StreamController<ArState> states;
  late ArState current;
  late List<MethodCall> calls;
  late MockImageDio imageDio;

  setUp(() {
    calls = [];
    states = StreamController<ArState>.broadcast();
    cubit = MockArCubit();
    imageDio = MockImageDio();
    when(
      () => imageDio.get<List<int>>(any(), options: any(named: 'options')),
    ).thenAnswer(
      (_) async => Response<List<int>>(
        data: [1, 2, 3],
        requestOptions: RequestOptions(path: '/media'),
      ),
    );
    current = ArState(
      status: ArStatus.storyPlaced,
      story: arTestStory,
      pages: arTestPages,
      placedPlaneId: 'table',
      isAnchorTracking: true,
    );
    when(() => cubit.state).thenAnswer((_) => current);
    when(() => cubit.stream).thenAnswer((_) => states.stream);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(ArSceneController.channel, (call) async {
          calls.add(call);
          return true;
        });
  });

  tearDown(() async {
    await states.close();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(ArSceneController.channel, null);
  });

  Future<void> mount(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: BlocProvider<ArCubit>.value(
          value: cubit,
          child: ArStoryViewerScreen(
            storyId: arTestStoryId,
            onExit: () {},
            useNativeScene: true,
            sceneController: ArSceneController(dio: imageDio),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'native mode has no camera-following card and page changes preserve the anchor',
    (tester) async {
      await mount(tester);
      expect(find.byType(ArStoryCard3d), findsNothing);
      expect(calls.where((c) => c.method == 'setStoryPage').length, 1);
      expect((calls.first.arguments as Map)['imageBytes'], [1, 2, 3]);
      current = current.copyWith(cameraTrackingState: 'PAUSED');
      states.add(current);
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'setStoryPage').length, 1);
      current = current.copyWith(currentPageIndex: 1);
      states.add(current);
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'setStoryPage').length, 2);
      expect(calls.where((c) => c.method != 'setStoryPage'), isEmpty);
    },
  );

  testWidgets(
    'physics, stand-up and camera-free preview send independent scene commands',
    (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('ar_physics_toggle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ar_stand_up')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('ar_preview_toggle')));
      await tester.pumpAndSettle();
      expect(find.text('Sin física'), findsOneWidget);
      await tester.tap(find.byKey(const Key('ar_preview_toggle')));
      await tester.pumpAndSettle();
      expect(find.text('Física: no'), findsOneWidget);
      final commands = calls
          .where((c) => c.method == 'storySceneCommand')
          .map((c) => c.arguments as Map)
          .toList();
      expect(commands.map((m) => m['command']), [
        'physics',
        'stand',
        'preview',
        'preview',
      ]);
      expect(commands[2]['enabled'], true);
      expect(commands[3]['enabled'], false);
    },
  );

  testWidgets(
    'dragging moves along the table, and rotate mode changes orientation instead',
    (tester) async {
      await mount(tester);
      await tester.dragFrom(const Offset(260, 320), const Offset(60, 30));
      await tester.pumpAndSettle();
      var transforms = calls
          .where(
            (c) =>
                c.method == 'storySceneCommand' &&
                (c.arguments as Map)['command'] == 'transform',
          )
          .toList();
      expect(transforms, isNotEmpty);
      expect((transforms.last.arguments as Map)['move'], true);
      await tester.tap(find.byKey(const Key('ar_move_rotate')));
      await tester.pumpAndSettle();
      await tester.dragFrom(const Offset(260, 320), const Offset(50, 60));
      await tester.pumpAndSettle();
      transforms = calls
          .where(
            (c) =>
                c.method == 'storySceneCommand' &&
                (c.arguments as Map)['command'] == 'transform',
          )
          .toList();
      expect((transforms.last.arguments as Map)['move'], false);
      expect((transforms.last.arguments as Map)['tilt'], isNot(0));
      expect((calls.last.arguments as Map)['command'], 'end');
    },
  );
}
