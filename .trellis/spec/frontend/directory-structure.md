# Frontend directory structure
Status: approved greenfield convention. Use apps/web, React, TypeScript strict and Vite.
Organize src/features by project, workbench, editor, versions, assets, slides and video.
Shared controls live in src/components; API boundary and runtime validators in src/api.
Place generated applications outside the workbench source tree and display them only in an iframe.

Actual foundation: `apps/web/src/features/home/HomePage.tsx` and `useCapabilities.ts`,
`src/api/capabilities.ts`, `src/components/Icon.tsx`, `src/styles.css`, and `src/main.tsx`.
Hash routes in `features/projects/navigation.ts` include project lists, details and chat.
Project persistence uses `api/projects.ts`; generated content uses sandboxed preview frames.
`features/projects/ProjectActionComposer.tsx` is shared by project details and follow-up chat.
Full source/visual editing and public publication remain planned.
`ProjectVersionHistory.tsx` and `api/versions.ts` provide history, confirmed restore
and WEB/GAME ZIP downloads. `ProjectPreview` and chat `VideoPreview` bind explicit
version IDs to preview sessions; selecting an older version never changes the
current working version.
