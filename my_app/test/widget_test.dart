import 'package:flutter_test/flutter_test.dart';
import 'package:my_app/main.dart';

void main() {
  testWidgets('shows scam call dashboard', (WidgetTester tester) async {
    await tester.pumpWidget(const ScamCallApp());

    expect(find.text('Scam Call Guard'), findsOneWidget);
    expect(find.text('Voice Detect'), findsOneWidget);
    expect(find.text('Text Detect'), findsOneWidget);
    expect(find.text('Total Detect'), findsOneWidget);
  });
}
