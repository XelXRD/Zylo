-- AlterTable
ALTER TABLE "Profile" ADD COLUMN     "settings" JSONB NOT NULL DEFAULT '{}';

-- CreateTable
CREATE TABLE "PlaybackProgress" (
    "profileId" TEXT NOT NULL,
    "mediaItemId" TEXT NOT NULL,
    "seasonNumber" INTEGER NOT NULL DEFAULT -1,
    "episodeNumber" INTEGER NOT NULL DEFAULT -1,
    "position" DOUBLE PRECISION NOT NULL,
    "duration" DOUBLE PRECISION NOT NULL,
    "completed" BOOLEAN NOT NULL DEFAULT false,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PlaybackProgress_pkey" PRIMARY KEY ("profileId","mediaItemId","seasonNumber","episodeNumber")
);

-- CreateTable
CREATE TABLE "SourceChoice" (
    "profileId" TEXT NOT NULL,
    "mediaItemId" TEXT NOT NULL,
    "addonId" TEXT NOT NULL,
    "quality" TEXT,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SourceChoice_pkey" PRIMARY KEY ("profileId","mediaItemId")
);

-- CreateIndex
CREATE INDEX "PlaybackProgress_profileId_updatedAt_idx" ON "PlaybackProgress"("profileId", "updatedAt");

-- AddForeignKey
ALTER TABLE "PlaybackProgress" ADD CONSTRAINT "PlaybackProgress_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "PlaybackProgress" ADD CONSTRAINT "PlaybackProgress_mediaItemId_fkey" FOREIGN KEY ("mediaItemId") REFERENCES "MediaItem"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SourceChoice" ADD CONSTRAINT "SourceChoice_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SourceChoice" ADD CONSTRAINT "SourceChoice_mediaItemId_fkey" FOREIGN KEY ("mediaItemId") REFERENCES "MediaItem"("id") ON DELETE CASCADE ON UPDATE CASCADE;
