# Product Requirements Document (PRD)

## Product Name
**Chorevo (LearnSense)**

## Document Version
**v2.0 Combined PRD**

## Product Owner
INF2007 Team 03

## Date
April 16, 2026

---

## 1. Executive Summary

Chorevo is an AI-enabled mobile dance learning and movement coaching platform designed to help users practise and improve dance or other movement-based skills using their smartphones. The application combines instructor-uploaded reference videos, on-device pose estimation, guided practice, post-session analytics, and engagement features such as streaks, leaderboards, reminders, and achievements.

Unlike conventional video-based dance tutorial platforms, Chorevo does not rely entirely on learner self-assessment. Instead, it uses MediaPipe pose landmark extraction and motion comparison to evaluate the learner's performance against a reference dance and generate objective scores and feedback. The application is built as a native Android app using Kotlin, with Firebase services for authentication, storage, metadata persistence, analytics-related records, and synchronisation.

This PRD combines the current Chorevo report structure with additional product, functional, non-functional, privacy, and security requirements so it can serve as a fuller product blueprint for implementation, validation, and evaluation.

---

## 2. Product Overview

### 2.1 Product Summary
Chorevo is a mobile-based digital coaching platform for dance and movement learning. Users can upload reference dance videos, browse a content library, practise by following a selected dance, and receive AI-assisted feedback based on pose comparison between their movement and the reference choreography.

### 2.2 Problem Statement
Current video-based dance learning tools are strong at content delivery but weak at performance evaluation. Learners can watch tutorials repeatedly, but they often do not know whether their timing, posture, alignment, smoothness, or execution is correct. This causes repeated mistakes, slow improvement, and heavy reliance on subjective self-assessment.

There is a need for a mobile learning application that:
- allows instructors or creators to upload dance tutorials,
- allows learners to practise directly against those tutorials,
- evaluates movement accuracy using pose landmarks,
- presents feedback in an understandable and motivating way,
- supports habit formation and long-term user engagement.

### 2.3 Product Vision
To transform the smartphone into an accessible, intelligent, and practical movement-learning coach that helps users learn dance and other movement-based skills through guided practice, performance analysis, and progress tracking.

### 2.4 Product Positioning
Chorevo sits between three existing categories:
- **Video learning platforms** that provide tutorials but little objective feedback,
- **Dance exergames** that provide fun and scoring but limited technical coaching,
- **Pose-estimation demos or proof-of-concepts** that show movement comparison without a complete learner-centred product workflow.

Chorevo combines guided practice, on-device movement analysis, post-session review, and engagement features into a learner-friendly mobile application.

---

## 3. Background and Motivation

The application is motivated by the growing need for accessible and flexible physical skill learning, especially for working adults or independent learners with limited time, money, or access to formal instruction. Traditional dance studios, coaching sessions, and structured classes can be costly and inflexible. At the same time, passive tutorial videos are often insufficient for correcting mistakes.

Recent improvements in mobile AI and pose estimation make it possible to analyse body movement directly on consumer smartphones. This creates a strong opportunity to build a self-paced digital coaching platform that provides both convenience and feedback-driven learning.

Chorevo also recognises that learning success depends not only on analysis accuracy but also on motivation. Therefore, the product includes features such as reminders, streak tracking, leaderboards, badges, and progress visibility to encourage continued participation.

---

## 4. Goals and Objectives

### 4.1 Business Goals
- Build a scalable AI-assisted dance learning platform.
- Encourage user-generated instructional content.
- Improve retention through progress tracking and repeated practice.
- Differentiate from standard video tutorial platforms with objective scoring and feedback.
- Build a reusable foundation for extension into other movement domains such as fitness, yoga, rehabilitation, and sports drills.

### 4.2 Product Goals
- Enable instructors or creators to upload dance reference videos.
- Extract reference pose landmarks from uploaded content.
- Allow learners to browse and select dance lessons.
- Support guided practice with live camera-based capture.
- Compare learner movement against reference movement.
- Provide scores and readable performance analysis.
- Preserve user progress across sessions.
- Support engagement through reminders, achievements, and leaderboards.
- Protect user data, private recordings, and scoring integrity.

### 4.3 User Goals
- Learn dances in a flexible home-based environment.
- Receive objective feedback instead of guessing performance quality.
- Identify weak segments and retry them.
- Track progress over time.
- Stay motivated through visible improvement and rewards.

### 4.4 Success Metrics
- Number of uploaded dance videos
- Number of practice attempts per user
- Average weekly active users
- Practice completion rate
- Retry rate for previously attempted dances
- Retention rate after first week and first month
- Percentage of users who view analytics after a session
- Percentage of users with weekly streak activity
- Reduction in score disputes or security incidents
- Successful sync rate for session uploads

---

## 5. Target Users

### 5.1 Primary Users

#### Dance Learners
Users who want to learn dance routines by following instructor or creator videos and receiving AI-assisted feedback on their performance.

#### Dance Instructors / Dance Creators
Users who upload reference dance videos for learners to practise against.

### 5.2 Secondary Users

#### Progress-Focused Users
Users who want to improve over time and compare their own attempts, scores, and segment analysis.

#### Community Users
Users who participate in rankings, leaderboards, challenges, likes, follows, or social progress sharing.

#### Future Movement-Learning Users
Potential future users in adjacent domains such as yoga, exercise, rehabilitation, and sport technique training.

---

## 6. Product Scope

### 6.1 In Scope
- Authentication and profile management
- Instructor / creator dance upload
- Dance library browsing and search
- Practice mode with camera input
- On-device pose extraction with MediaPipe
- Reference vs learner movement comparison
- Real-time or near-real-time skeletal overlay
- Post-session analytics and feedback
- Practice history and progress tracking
- Leaderboards, streaks, badges, reminders
- Local persistence and cloud sync
- Content visibility controls
- Reporting and moderation
- Secure storage and access control

### 6.2 Out of Scope for MVP
- Full multi-person or group choreography evaluation
- Advanced hand/finger choreography scoring
- Full facial-expression scoring
- Multi-camera capture
- Live coach voice assistant
- Paid subscriptions / monetization
- Web application
- Professional marketplace features
- Fully automated music beat alignment model

### 6.3 Future Scope
- Friend challenges
- Group practice sessions
- Personalised training plans
- Adaptive difficulty
- Expanded social features
- Genre-aware scoring models
- Additional movement domains beyond dance

---

## 7. Core Value Proposition

Chorevo provides:
1. **Guided dance learning** through reference video content,
2. **Objective performance evaluation** through pose-based movement analysis,
3. **Understandable feedback** through segment and body-part summaries,
4. **Habit-supporting engagement features** to encourage repeated practice,
5. **Mobile accessibility** through on-device AI and offline-first behaviour.

---

## 8. User Stories

### 8.1 Existing Core User Stories
- **US1**: As an instructor, I want to upload reference dance videos so that learners can practise against them.
- **US2**: As a learner, I want to browse available learning videos so that I can choose a dance to learn.
- **US3**: As a learner, I want to select a dance video and start practising so that I can follow a reference routine.
- **US4**: As a learner, I want the app to record my movement using the camera so that my performance can be analysed.
- **US5**: As a learner, I want the system to track my movement frame by frame so that it can compare my dance accurately.
- **US6**: As a learner, I want AI-guided pose comparison so that I receive a meaningful score and feedback.
- **US7**: As a learner, I want to review performance by movement segment so that I know which parts need improvement.
- **US8**: As a learner, I want leaderboards and progress tracking so that I stay motivated.
- **US9**: As a learner, I want reminder notifications so that I remember to practise.
- **US10**: As a learner, I want daily streak tracking so that I can maintain consistency.
- **US11**: As a learner, I want achievements and badges so that I feel rewarded for progress.
- **US12**: As a learner, I want notification settings so that I can personalise reminders.

### 8.2 Additional Product User Stories
- As a user, I want to mark dances as favourites so that I can revisit them easily.
- As a learner, I want to compare my current attempt with previous attempts so that I can see improvement.
- As a learner, I want body-part and timing sub-scores so that I know what exactly went wrong.
- As a learner, I want private practice attempts to remain visible only to me unless I choose to share them.
- As a creator, I want to edit or unpublish my uploaded dance videos so that I can manage my content.
- As a creator, I want to tag my videos by dance style and difficulty so that users can find them more easily.
- As an admin, I want reported content to be reviewed and moderated so that the platform remains safe.
- As an admin, I want to suspend abusive users so that misuse is limited.
- As a learner, I want the app to still preserve my results even if the network fails so that I do not lose progress.
- As a learner, I want practice playback features like retry and review so that I can improve weak areas.

---

## 9. Functional Requirements

### 9.1 Account and Authentication
The system shall:
- allow users to register and log in,
- support authenticated access to protected features,
- allow logout and account session management,
- associate all uploads, practice attempts, analytics, and profile data with an authenticated user,
- support role-aware access for learners, creators/instructors, and admins where required.

### 9.2 User Profile
The system shall:
- display a user profile page,
- allow a user to edit their own profile only,
- support profile picture, display name, bio, skill level, and account details,
- display practice history, uploaded videos, achievements, and streak information,
- support notification preference management.

### 9.3 Dance Video Upload and Management
The system shall:
- allow creators/instructors to upload reference dance videos,
- collect metadata such as title, description, dance type/style, difficulty, duration, and visibility,
- generate or trigger pose landmark extraction for uploaded reference videos,
- store video files separately from metadata,
- support update, unpublish, or delete actions for the owner's uploads,
- validate video file type and file size before upload.

### 9.4 Dance Library and Discovery
The system shall:
- display a browsable library of available dance content,
- support selection of a video for practice,
- support search by title, creator, dance style, or tag,
- support filtering by difficulty, category, or duration,
- support favourites/bookmarks,
- support a home feed or recommended list for discovery.

### 9.5 Practice Mode
The system shall:
- allow a learner to enter a practice session for a selected dance,
- play the reference video during practice,
- show live camera preview through CameraX,
- render skeletal overlays for user guidance,
- capture learner movement during practice,
- allow session start, pause, submit, and reset/retry flows,
- support practice review after submission.

### 9.6 Pose Landmark Extraction
The system shall:
- extract pose landmarks from instructor reference videos,
- extract pose landmarks from live learner practice,
- support frame-by-frame movement analysis,
- handle low-confidence or missing frame data gracefully,
- normalise or adjust pose data to reduce bias from camera distance, framing, and body size differences.

### 9.7 Movement Comparison and Scoring
The system shall:
- compare learner movement against the reference template,
- compute similarity metrics such as alignment, timing, smoothness, and pose similarity,
- support an overall score,
- support segment-based analysis,
- support at least the following sub-scores where feasible:
  - upper body,
  - lower body,
  - posture/alignment,
  - timing,
  - smoothness,
- align movement sequences so late starts or slight offsets do not unfairly reduce scores,
- support weighted movement scoring for important joints or segments.

### 9.8 Analytics and Feedback
The system shall:
- provide a post-session analytics dashboard,
- present performance summaries in understandable terms,
- identify weak segments,
- indicate where movement needs improvement,
- support comparison with reference content,
- support historical performance review across attempts.

### 9.9 Progress Tracking
The system shall:
- store learner practice attempts,
- store session scores and analysis metrics,
- display progress over time,
- display best scores and recent scores,
- support streak tracking and milestone recognition.

### 9.10 Engagement Features
The system shall:
- maintain leaderboards,
- award badges or achievements,
- support daily streak tracking,
- allow users to configure notification preferences,
- send reminder notifications to encourage regular practice.

### 9.11 Social and Community Features
The system should:
- support likes or basic engagement on public content,
- support following creators or future friend systems,
- support sharing progress or challenge participation in future phases,
- support public ranking views while keeping private session details controlled.

### 9.12 Content Moderation
The system shall:
- allow users to report content or users,
- allow moderators/admins to review reports,
- allow admins to remove flagged content,
- maintain moderation decision records.

### 9.13 Offline-First and Synchronisation
The system shall:
- save completed learner sessions locally first,
- preserve unsynced records locally if connectivity fails,
- retry synchronisation later,
- indicate sync status where relevant,
- prevent loss of progress due to temporary network issues.

---

## 10. Non-Functional Requirements

### 10.1 Performance
- Startup time should be acceptable for regular mobile usage.
- Practice mode should remain responsive during camera and pose processing.
- Post-session scoring and analytics should complete within a reasonable time.
- Video browsing and playback should remain usable under typical mobile network conditions.

### 10.2 Reliability
- Practice results should not be lost due to temporary connectivity issues.
- The app should preserve local data until sync completes.
- Core features should continue to function stably during repeated use.

### 10.3 Usability
- The product should be understandable for non-expert users.
- Analytics should be interpretable without specialist knowledge of motion tracking.
- UI flow should support quick navigation from selecting a dance to practising and reviewing results.

### 10.4 Maintainability
- The codebase should separate presentation, business logic, and data access concerns.
- Functional areas should remain modular enough for independent extension and refactoring.

### 10.5 Scalability
- The backend should support increasing amounts of video content and learner records.
- The data model should support large growth in session logs, scores, and engagement records.

### 10.6 Portability / Extensibility
- The architecture should support future extensions into other movement-learning domains.
- The scoring pipeline should be extensible for more advanced algorithms.

### 10.7 Testability
- Key repositories, ViewModels, local persistence, and major user-facing workflows should be testable through unit, instrumented, and integration tests.
- CI should validate builds and catch regressions.

### 10.8 Accessibility
- Navigation and text should be clear enough for general mobile usage.
- Visual guidance elements should remain understandable across different screen sizes.

---

## 11. Security Requirements

### 11.1 Authentication
The system shall:
- require authentication for protected actions such as uploading, deleting, and viewing private practice history,
- prevent unauthenticated access to private user records.

### 11.2 Authorization
The system shall:
- ensure users can edit only their own profile,
- ensure creators can edit only their own uploaded videos unless an admin intervenes,
- ensure private practice attempts are visible only to their owner unless explicitly shared,
- enforce admin or moderator privileges through trusted mechanisms, not only client UI checks.

### 11.3 Secure Storage and Media Access
The system shall:
- store videos securely using Firebase Storage,
- keep private learner attempts and private uploads inaccessible to unauthorised users,
- separate public content access from private attempt data where possible,
- validate file ownership, type, and size before completing upload workflows.

### 11.4 Secure Database Rules
The system shall:
- enforce Firestore access with strict per-user and role-based rules,
- prevent users from modifying records they do not own,
- restrict updates to approved fields,
- prevent clients from self-assigning privileged roles.

### 11.5 Score Integrity
The system shall:
- avoid treating client-submitted score values as trusted final truth,
- recompute or verify official scores in a trusted workflow where needed,
- store scoring version information so results remain auditable and explainable.

### 11.6 App Abuse Prevention
The system shall:
- rate-limit abusive activity where feasible,
- reduce exposure to scripted or fake-client abuse through app attestation mechanisms,
- support Firebase App Check or equivalent protection,
- log suspicious activity for review where appropriate.

### 11.7 Secrets and Privileged Logic
The system shall:
- not embed privileged backend credentials in the client,
- keep sensitive backend operations outside the mobile app,
- protect secret keys and privileged configuration.

### 11.8 Moderation and Safety
The system shall:
- support user reporting,
- maintain moderation logs,
- allow removal of harmful or inappropriate content,
- support suspension of abusive users.

### 11.9 Auditability
The system shall:
- log important security-relevant actions such as moderation actions, deletion events, and failed privileged operations,
- retain enough information for investigation of abuse or disputes.

---

## 12. Privacy Requirements

### 12.1 Personal and Sensitive Data
Although pose landmarks are not equivalent to full raw video, they still represent body movement and can reveal sensitive behavioural or physical information. Raw practice videos may be even more sensitive.

### 12.2 Privacy Controls
The system shall:
- clearly inform users what data is collected and stored,
- support public, private, and unlisted visibility settings for dance videos where applicable,
- allow users to delete their own uploads and practice attempts,
- request explicit camera permission and explain its use,
- minimise raw video retention where not strictly necessary.

### 12.3 Data Minimisation
The system should:
- store only the data needed for learning, analytics, and progress tracking,
- avoid unnecessary duplication of raw learner recordings if landmark-only persistence is sufficient for some use cases.

### 12.4 User Data Separation
The system shall:
- separate creator reference content from learner private attempts,
- separate public leaderboards from detailed private analytics,
- preserve ownership and visibility metadata for all user-generated content.

---

## 13. Product Differentiation

### 13.1 Compared to Video-Only Platforms
Chorevo improves on video-only learning by adding:
- pose comparison,
- score generation,
- performance analytics,
- segment review,
- structured progress tracking.

### 13.2 Compared to Exergames
Chorevo improves on entertainment-focused dance systems by:
- focusing on technique awareness rather than only fun/gameplay score,
- providing post-session analysis instead of only broad scoring,
- supporting deliberate practice and review.

### 13.3 Compared to Pose Demo Systems
Chorevo improves on technical demos by:
- offering a full learner workflow,
- supporting uploads, browsing, practice, analytics, progress, and engagement,
- providing a polished mobile-first product rather than a proof-of-concept.

---

## 14. Technical Architecture

### 14.1 Architecture Style
Chorevo follows a layered mobile architecture:
- **Presentation Layer**
- **Business Layer**
- **Data Access Layer**

### 14.2 Presentation Layer
Responsible for:
- authentication screens,
- dance library browsing,
- upload screens,
- live AR practice interface,
- analytics dashboard,
- leaderboard views,
- profile and settings pages.

### 14.3 Business Layer
Responsible for:
- dance session orchestration,
- pose detection coordination,
- movement analysis and similarity scoring,
- analytics computation,
- notification logic,
- streaks, badges, and progress logic,
- profile and content workflow coordination.

### 14.4 Data Access Layer
Responsible for:
- CameraX camera feed integration,
- local Room persistence,
- DataStore preferences,
- Firebase Authentication,
- Firebase Storage,
- Cloud Firestore,
- sync workflows and repositories.

### 14.5 Architectural Principles
- Clear separation of concerns
- Modular MVVM-style implementation
- Offline-first learner result handling
- On-device pose analysis for responsiveness
- Cloud-backed storage and sync for scalability

---

## 15. Technology Stack

### 15.1 Client / Frontend
- Kotlin
- Jetpack Compose
- ViewModel + StateFlow
- CameraX
- Media3 / ExoPlayer

### 15.2 AI / Movement Analysis
- MediaPipe Tasks Vision Pose
- Custom pose similarity evaluator

### 15.3 Data and Backend Services
- Firebase Authentication
- Cloud Firestore
- Firebase Storage
- Firebase App Check
- Room
- DataStore

### 15.4 Notifications / Scheduling
- Android AlarmManager
- Android notification components

### 15.5 Testing / Delivery
- Unit tests
- Instrumented tests
- GitHub Actions CI

---

## 16. Data Flow

### 16.1 Instructor Upload Flow
1. Creator selects and uploads a dance video.
2. The app extracts or generates pose landmark templates from sampled video frames.
3. Video is uploaded to Firebase Storage.
4. Metadata and pose template are stored in Firestore.
5. The uploaded dance becomes available in the library according to visibility settings.

### 16.2 Learner Practice Flow
1. Learner selects a dance from the library.
2. The app retrieves the reference video and movement template.
3. CameraX captures live learner movement.
4. MediaPipe extracts pose landmarks on-device.
5. The movement comparison module evaluates learner pose against the reference.
6. Real-time or near-real-time guidance is shown through skeletal overlay and live metrics where applicable.
7. On submission, session results are saved locally first.
8. Session data is synced to Firestore when possible.
9. Analytics, streaks, leaderboard values, badges, and progress records are updated.

---

## 17. Proposed Data Model

### 17.1 Users
- userId
- displayName
- email
- avatarUrl
- bio
- skillLevel
- role
- createdAt
- updatedAt

### 17.2 DanceVideos
- danceId
- ownerId
- title
- description
- danceStyle
- difficulty
- duration
- thumbnailUrl
- videoUrl
- visibility
- createdAt
- updatedAt

### 17.3 DanceLandmarks
- danceId
- poseTemplate
- extractionVersion
- frameCount
- metadata

### 17.4 PracticeAttempts
- attemptId
- userId
- danceId
- localSyncStatus
- attemptVideoUrl (optional/private)
- createdAt
- submittedAt

### 17.5 AttemptScores
- scoreId
- attemptId
- overallScore
- alignmentScore
- timingScore
- smoothnessScore
- upperBodyScore
- lowerBodyScore
- postureScore
- feedbackSummary
- scoringVersion

### 17.6 AttemptSegments
- attemptId
- segmentId
- startTime
- endTime
- segmentScore
- segmentFeedback

### 17.7 Achievements
- achievementId
- userId
- achievementType
- unlockedAt
- pointsAwarded

### 17.8 Streaks
- userId
- currentStreak
- longestStreak
- lastPracticeDate

### 17.9 Leaderboards
- boardId
- userId
- rankingMetric
- value
- updatedAt

### 17.10 Notifications / Preferences
- userId
- pushEnabled
- reminderTime
- notificationPreferences

### 17.11 Reports
- reportId
- reporterId
- targetType
- targetId
- reason
- status
- createdAt
- resolvedAt

---

## 18. Feature Breakdown by User

### 18.1 Learner Features
- Sign up / login
- Browse dance lessons
- Select a dance to practise
- Live AR practice view
- Pose tracking and scoring
- Analytics dashboard
- Practice history
- Streak tracking
- Leaderboards
- Badges / achievements
- Notification settings

### 18.2 Creator / Instructor Features
- Upload dance videos
- Generate reference templates
- Manage uploaded content
- View learner engagement data
- Edit or remove own uploaded content

### 18.3 Admin / Moderator Features
- Review reports
- Remove content
- Suspend users
- Inspect moderation logs

---

## 19. MVP Definition

### 19.1 MVP Feature Set
- User registration and login
- Creator upload of dance videos
- Dance library browsing and selection
- Camera-based learner practice
- MediaPipe pose extraction
- Reference vs learner comparison
- Overall score and basic sub-score output
- Post-session analytics summary
- Practice history
- Streak tracking
- Leaderboard
- Reminder notifications
- Local save + sync to cloud

### 19.2 MVP Security Set
- Firebase Authentication
- Firestore and Storage security rules
- File validation
- Per-user access control
- Firebase App Check
- Private learner attempt access controls
- Basic moderation/reporting

### 19.3 MVP Acceptance Threshold
The MVP is acceptable if:
- a user can authenticate,
- a creator can upload a dance,
- a learner can practise against it,
- the system can extract landmarks and compute meaningful scores,
- analytics can be viewed after a session,
- results are preserved locally and synced later,
- private data remains inaccessible to unauthorised users.

---

## 20. Roadmap / Sprint Alignment

### 20.1 Sprint 1 - Foundation
- Project setup
- Firebase integration
- Authentication
- MediaPipe integration
- Video upload
- Pose landmark generation
- Core UI pages
- Video browsing and selection

### 20.2 Sprint 2 - Core AI Learning
- Live camera view
- Learner movement capture
- AR skeleton overlay
- Video playback
- Movement comparison
- Similarity scoring
- Analytics processing
- Dashboard improvements

### 20.3 Sprint 3 - Engagement and Refinement
- Streak tracking
- Leaderboards
- Achievement system
- Notification system
- Profile improvements
- Analytics fixes
- Upload page improvements
- Bug fixing
- Testing
- Launch preparation

---

## 21. Testing and Validation Requirements

### 21.1 Testing Strategy
The product shall be validated through:
- manual testing on emulator and physical devices,
- unit testing,
- instrumented Android testing,
- integration-style testing for key workflows,
- CI automation through GitHub Actions.

### 21.2 Unit Test Coverage Areas
Should include:
- authentication repository logic,
- dance library retrieval logic,
- video upload workflows,
- analytics dashboard state logic,
- coroutine and state-handling helpers.

### 21.3 Instrumented / Integration Test Coverage Areas
Should include:
- authentication screens,
- dance library screen,
- upload screen,
- AR practice flow,
- analytics dashboard flow,
- Room DAO operations.

### 21.4 Quality Expectations
- important logic components should be testable in isolation,
- critical user-facing flows should work reliably in Android runtime,
- code changes should pass CI validation before merge.

---

## 22. Performance Requirements and Observed Prototype Benchmarks

### 22.1 Target Performance Expectations
- App launch should be reasonably fast for common mobile use.
- Core screen interactions should remain mostly responsive.
- Firebase sync should usually succeed under normal connectivity.
- Repeated navigation should not cause runaway memory growth.

### 22.2 Prototype Performance Notes
Observed evaluation in the current report indicates:
- startup around **1.482 seconds**,
- generally responsive core interaction with some janky frames,
- functional Firebase communication with some sync improvement opportunities,
- generally stable runtime memory behaviour during repeated use.

These prototype results are acceptable for the current phase, but future optimisation should focus on:
- rendering smoothness,
- sync robustness,
- memory overhead reduction,
- performance consistency under heavy camera/analytics activity.

---

## 23. Risks and Challenges

### 23.1 Technical Risks
- Pose accuracy may degrade under poor lighting or bad framing.
- Camera angle and body size differences may affect fairness if normalisation is weak.
- Sequence alignment errors may unfairly penalise learners.
- Real-time UI plus pose processing may introduce jank on lower-end devices.
- Cloud sync failures may create inconsistent records if retry handling is weak.

### 23.2 Product Risks
- Users may not trust scores if feedback is too vague.
- Overly strict scoring may discourage beginners.
- Weak discovery and engagement features may reduce retention.
- Creators may not upload enough content if content management is weak.

### 23.3 Security Risks
- Weak Firebase rules could expose private videos or practice attempts.
- Client-side score tampering could undermine trust.
- Abuse through spam uploads or fake clients could increase costs and moderation burden.

### 23.4 Operational Risks
- Rising storage and analytics volume may increase backend costs.
- Content moderation workload may grow with adoption.

---

## 24. Future Enhancements

- More accurate temporal movement analysis
- Adaptive difficulty and personalised feedback
- Personalised learning plans
- Friend system and social challenges
- Group practice and group choreography analysis
- Expansion into fitness, yoga, rehabilitation, and sports drills
- More advanced motion domains such as hand/finger tracking
- Coach-to-learner interaction
- Music beat alignment and rhythm analysis
- More explainable AI feedback generation

---

## 25. Acceptance Criteria

A release can be considered successful if:
- users can authenticate successfully,
- creators can upload and manage dance videos,
- learners can browse and select content,
- practice mode captures user movement reliably,
- pose landmarks are extracted from both reference and learner input,
- the app generates meaningful scores and readable analytics,
- progress history is persisted and viewable,
- reminder and streak features function,
- leaderboard data updates correctly,
- local-first persistence prevents immediate data loss when the network is unstable,
- private data remains protected from unauthorised access,
- reporting and moderation workflows are operational.

---

## 26. Summary

Chorevo is more than a tutorial-viewing app. It is a mobile-first AI-assisted digital coaching platform that combines on-device pose estimation, guided dance practice, analytics, engagement systems, and cloud-backed progress tracking. Its design addresses both the technical problem of movement evaluation and the behavioural problem of sustaining regular practice.

This combined PRD consolidates the current product direction, implemented architecture, user stories, testing approach, and performance observations with a fuller set of product, functional, non-functional, privacy, and security requirements. It can serve as a working specification for refinement, implementation planning, testing, and future expansion.
