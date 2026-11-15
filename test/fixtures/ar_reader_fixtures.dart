import 'package:startale_mobile/features/reader/domain/entities/story_detail.dart';
import 'package:startale_mobile/features/reader/domain/entities/story_page_detail.dart';

const arTestStoryId = 'story-test';
final arTestStory = StoryDetail(
  id: arTestStoryId,
  title: 'A test story',
  description: 'Reader test fixture',
  createdAt: DateTime.utc(2026),
  updatedAt: DateTime.utc(2026),
);
final arTestPages = List.generate(
  2,
  (index) => StoryPageDetail(
    id: 'page-${index + 1}',
    storyId: arTestStoryId,
    pageNumber: index + 1,
    action: 'Test scene ${index + 1}',
    text: 'Story page ${index + 1}.',
    visualConfig: {'asset_id': 'image-${index + 1}'},
    characterIds: const [],
    createdAt: DateTime.utc(2026),
    updatedAt: DateTime.utc(2026),
  ),
);
