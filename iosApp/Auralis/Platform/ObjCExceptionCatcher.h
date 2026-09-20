#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// NSException is not a Swift Error. installTap / removeTap throw this on format mismatch.
@interface AuralisExceptionCatcher : NSObject
+ (BOOL)run:(void(NS_NOESCAPE ^)(void))block error:(NSError *_Nullable *_Nullable)error;
@end

NS_ASSUME_NONNULL_END
