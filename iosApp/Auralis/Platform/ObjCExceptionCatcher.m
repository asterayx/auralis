#import "ObjCExceptionCatcher.h"

@implementation AuralisExceptionCatcher

+ (BOOL)run:(void(NS_NOESCAPE ^)(void))block error:(NSError *_Nullable *_Nullable)error {
    @try {
        block();
        return YES;
    } @catch (NSException *exception) {
        if (error != NULL) {
            *error = [NSError errorWithDomain:exception.name ?: @"NSException"
                                        code:0
                                    userInfo:@{
                NSLocalizedDescriptionKey: exception.reason ?: exception.name ?: @"exception",
            }];
        }
        return NO;
    }
}

@end
