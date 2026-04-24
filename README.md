# Quiz Leaderboard System — Java

## How to Run

### Prerequisites
- Java 11 or higher (uses built-in `java.net.http.HttpClient` — no Maven, no dependencies)

### Steps

```bash
# 1. Compile
javac src/main/java/com/quiz/QuizLeaderboard.java -d out

# 2. Run
java -cp out com.quiz.QuizLeaderboard
```
## Sample Output

```
=== Quiz Leaderboard System ===
Registration No : RA2311033010083
Polls to run    : 10
Poll interval   : 5s

[Poll 0] GET .../quiz/messages?regNo=RA2311033010083&poll=0
[Poll 0] Waiting 5s...

...

[Dedup] Total events received : 38
[Dedup] Duplicates skipped    : 18
[Dedup] Unique events counted : 20

─── Final Leaderboard ───────────────────────────
   1. Bob                  320
   2. Alice                280
   3. Charlie              210
   ...
  Combined Total Score : 2290
─────────────────────────────────────────────────

[Submit] POST .../quiz/submit
✅ Submission received by server.
`

